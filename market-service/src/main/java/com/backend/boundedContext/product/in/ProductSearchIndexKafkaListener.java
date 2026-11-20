package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.out.search.SearchIndexUnavailableException;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.member.event.SellerBusinessNameChangedEvent;
import com.backend.shared.product.event.ProductChangedEvent;
import com.backend.shared.product.event.ProductMetricsChangedEvent;
import com.backend.shared.product.event.WishAddedEvent;
import com.backend.shared.product.event.WishRemovedEvent;
import com.backend.shared.review.event.ReviewCreatedEvent;
import com.backend.shared.review.event.ReviewDeletedEvent;
import com.backend.shared.review.event.ReviewModifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 상품 색인 값이 바뀌는 이벤트를 Kafka로 받아 검색 색인에 반영한다 (engine=es일 때만).
 * <ul>
 *   <li>product.events: 상품 변경(ProductChangedEvent), 행동 지표 변경(ProductMetricsChangedEvent, 판매량), 찜 추가·해제(찜 수). 키가 상품 ID</li>
 *   <li>review.events: 리뷰 작성·수정·삭제 (리뷰 수·별점). 키가 상품 ID</li>
 *   <li>seller.events: 상호 변경(SellerBusinessNameChangedEvent). 키가 판매자 ID라 봉투에서 판매자 ID를 읽고 그 판매자의 판매 중 상품을 다시 색인한다</li>
 * </ul>
 * <p>
 * 메시지를 묶음으로 받아 상품 ID를 모은 뒤 한 번에 색인한다. payload는 쓰지 않고 상품 ID로 DB를 다시 읽어 문서 전체를 다시 쓴다
 * (판매 중이 아니면 지운다). 같은 이벤트가 다시 오거나 순서가 바뀌어도 결과는 DB의 지금 상태이므로 processed_event로 거르지 않는다.
 * 그 밖의 이벤트는 eventType 헤더로 걸러 payload를 읽지 않는다. 헤더가 없으면 봉투를 읽어 거른다.
 * <p>
 * 실패는 두 가지로 나눈다.
 * <ul>
 *   <li>일시 오류(ES 접속·응답 실패, alias 없음, 이전 인덱스의 거부, DB 연결 실패): 시간이 지나면 성공하므로 예외를 던져
 *       묶음 전체를 성공할 때까지 다시 처리한다(ProductSearchIndexKafkaConfig). 이 그룹의 offset이 "어디까지 색인했는지"를 기억하므로
 *       ES가 돌아오거나 재색인이 끝나면 멈춘 위치부터 수동 재색인 없이 반영된다.</li>
 *   <li>그 밖의 오류(특정 상품 데이터 문제 등): 다시 해도 같으므로 상품 하나씩 다시 색인해 실패한 상품만 ERROR로 남기고 건너뛴다.
 *       한 상품 때문에 다른 상품의 색인이 멈추지 않는다. 건너뛴 상품은 다음 변경이나 관리자 전체 재색인으로 복구한다.</li>
 * </ul>
 * 판매자 리스너(market)·AI 추천과 offset을 따로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexKafkaListener {

    /** Kafka groupId */
    static final String CONSUMER = "market-search-index";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            ProductChangedEvent.class.getSimpleName(),
            ProductMetricsChangedEvent.class.getSimpleName(),
            WishAddedEvent.class.getSimpleName(),
            WishRemovedEvent.class.getSimpleName(),
            ReviewCreatedEvent.class.getSimpleName(),
            ReviewModifiedEvent.class.getSimpleName(),
            ReviewDeletedEvent.class.getSimpleName(),
            SellerBusinessNameChangedEvent.class.getSimpleName());
    private static final String BUSINESS_NAME_CHANGED = SellerBusinessNameChangedEvent.class.getSimpleName();

    private final ProductFacade productFacade;
    private final EventMessages eventMessages;
    private final ProductPolicy productPolicy;

    @KafkaListener(topics = {"product.events", "review.events", "seller.events"}, groupId = CONSUMER,
            containerFactory = ProductSearchIndexKafkaConfig.LISTENER_FACTORY)
    public void handle(List<ConsumerRecord<String, String>> records) {
        Set<Long> productIds = new LinkedHashSet<>();
        records.forEach(record -> productIds.addAll(changedProductIds(record)));
        if (productIds.isEmpty()) return;

        // 상호가 바뀐 판매자의 상품은 많을 수 있어 전체 재색인과 같은 크기로 나눠 색인한다
        List<Long> ids = List.copyOf(productIds);
        int batchSize = productPolicy.getSearchIndexBatchSize();
        for (int from = 0; from < ids.size(); from += batchSize) {
            index(ids.subList(from, Math.min(ids.size(), from + batchSize)));
        }
    }

    private void index(List<Long> productIds) {
        try {
            productFacade.indexProducts(productIds);
        } catch (RuntimeException e) {
            if (isTransient(e)) throw e;   // 묶음 전체를 다시 처리한다
            indexOneByOne(productIds);
        }
    }

    /** 상품 하나씩 색인해 실패한 상품만 건너뛴다. 일시 오류면 묶음 전체를 다시 처리하도록 던진다 */
    private void indexOneByOne(List<Long> productIds) {
        for (Long productId : productIds) {
            try {
                productFacade.indexProducts(Set.of(productId));
            } catch (RuntimeException e) {
                if (isTransient(e)) throw e;
                log.error("상품 검색 색인에 실패해 이 상품은 건너뜁니다. 다음 변경이나 관리자 전체 재색인으로 복구합니다. productId={}", productId, e);
            }
        }
    }

    /**
     * 다시 색인할 상품 ID. 상품·리뷰 이벤트면 메시지 키(상품 ID), 상호 변경이면 봉투에서 판매자 ID를 읽어 그 판매자의 판매 중 상품
     * (이 이벤트의 키는 판매자 ID라 상품 ID로 쓰면 안 된다), 다루지 않는 이벤트면 빈 목록.
     * 키나 봉투가 깨진 메시지는 다시 해도 같으므로 ERROR로 남기고 건너뛴다. 판매자 상품 조회 실패(DB 연결 등)는 던져 묶음 전체를 다시 처리한다
     */
    private List<Long> changedProductIds(ConsumerRecord<String, String> record) {
        Long sellerId;
        try {
            String eventType = eventType(record);
            if (!HANDLED_EVENT_TYPES.contains(eventType)) return List.of();
            if (!BUSINESS_NAME_CHANGED.equals(eventType)) return List.of(Long.valueOf(record.key()));
            if (!(eventMessages.read(record.value()).payload() instanceof SellerBusinessNameChangedEvent event)) return List.of();
            sellerId = event.sellerId();
        } catch (RuntimeException e) {
            log.error("상품 검색 색인 메시지를 읽지 못해 건너뜁니다. topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), e);
            return List.of();
        }
        return productFacade.getOnSaleProductIdsBySeller(sellerId);
    }

    private String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (header != null) return new String(header.value(), StandardCharsets.UTF_8);
        return eventMessages.read(record.value()).eventType();   // 콘솔로 다시 보낸 메시지처럼 헤더가 없을 때
    }

    /** 시간이 지나면 같은 요청이 성공하는 오류인지 (원인을 따라가며 본다) */
    static boolean isTransient(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SearchIndexUnavailableException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
