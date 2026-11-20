package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductEsIndexAdapter;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import com.backend.boundedContext.product.out.search.SearchIndexUnavailableException;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerBusinessNameChangedEvent;
import com.backend.shared.member.out.MemberApi;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

/**
 * 검색 색인 컨슈머가 product.events의 상품 변경 이벤트를 받아 색인하는지 확인한다.
 * 일시 오류(ES 장애)는 DLT 없이 성공할 때까지 다시 처리하고, 특정 상품의 영구 오류는 그 상품만 건너뛰고 다른 상품은 색인한다.
 * Kafka는 EmbeddedKafka, DB는 로컬 Docker PostgreSQL을 쓴다. 검색 엔진을 es로 켜되 ES 대신 색인 어댑터를 mock으로 바꾼다.
 * 릴레이(OutboxKafkaSender)와 같은 형식(키 productId, eventType 헤더)으로 직접 보낸다. 컨슈머는 payload를 읽지 않는다.
 */
@SpringBootTest(properties = {
        "custom.product.search.engine=es",
        "custom.scheduling.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "custom.product.search.indexConsumer.retryInitialIntervalMillis=100",
        "custom.product.search.indexConsumer.retryMaxIntervalMillis=200"
})
@EmbeddedKafka(topics = {"product.events", "review.events", "seller.events"}, partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ProductSearchIndexKafkaListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private OptionStockRepository optionStockRepository;
    @Autowired private EventMessages eventMessages;
    @Autowired private ObjectMapper objectMapper;

    /** ES 대신 색인 요청을 기록한다 (검색 어댑터도 이 타입을 받으므로 클래스로 바꾼다) */
    @MockitoBean
    private ProductEsIndexAdapter indexAdapter;

    /** 색인 문서의 상호명을 회원 서비스 대신 준다 */
    @MockitoBean
    private MemberApi memberApi;

    // 다른 테스트·시드와 겹치지 않는 판매자 ID
    private final long sellerId = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);

    private Long productId;
    private Long optionId;
    private Long otherProductId;
    private Long otherOptionId;

    @BeforeEach
    void setUp() {
        given(indexAdapter.isEnabled()).willReturn(true);
        transactionTemplate.executeWithoutResult(status -> {
            Product product = saveProduct("색인 컨슈머 테스트 크림");
            productId = product.getId();
            optionId = saveOption(product).getId();
            Product other = saveProduct("색인 컨슈머 테스트 토너");
            otherProductId = other.getId();
            otherOptionId = saveOption(other).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            productOptionRepository.deleteById(optionId);
            productRepository.deleteById(productId);
            productOptionRepository.deleteById(otherOptionId);
            productRepository.deleteById(otherProductId);
        });
    }

    @Test
    @DisplayName("색인이 공통 재시도 횟수(4번)보다 많이 실패해도(ES 장애) DLT로 보내지 않고 다시 처리해 결국 DB의 지금 값으로 색인한다")
    void retriesUntilIndexed() {
        SearchIndexUnavailableException esDown = new SearchIndexUnavailableException("ES 응답 없음");
        doThrow(esDown).doThrow(esDown).doThrow(esDown).doThrow(esDown).doThrow(esDown).doThrow(esDown)   // 6번 실패
                .doNothing()
                .when(indexAdapter).upsert(anyList());

        send(String.valueOf(productId), "ProductChangedEvent");

        await().atMost(WAIT).until(() -> upsertCalls() >= 7);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductSearchDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(indexAdapter, atLeast(7)).upsert(documents.capture());
        assertThat(documents.getAllValues().getLast())
                .extracting(ProductSearchDocument::productId, ProductSearchDocument::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(productId, "색인 컨슈머 테스트 크림"));
    }

    @Test
    @DisplayName("다루지 않는 이벤트는 색인하지 않고, 그 뒤 상품 변경 이벤트는 색인한다")
    void ignoresOtherEventTypes() {
        doNothing().when(indexAdapter).upsert(anyList());

        send(String.valueOf(productId), "OtherProductEvent");
        send(String.valueOf(productId), "ProductChangedEvent");   // 같은 키라 같은 파티션에서 뒤에 처리된다

        await().atMost(WAIT).until(() -> upsertCalls() >= 1);
        assertThat(upsertCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 상품의 색인이 계속 실패하는 영구 오류면 그 상품만 건너뛰고, 같은 묶음의 다른 상품과 뒤 메시지는 색인한다")
    void permanentErrorSkipsOnlyThatProduct() {
        doAnswer(invocation -> {
            List<ProductSearchDocument> documents = invocation.getArgument(0);
            if (documents.stream().anyMatch(doc -> doc.productId() == productId)) {
                throw new IllegalArgumentException("이 상품의 데이터 문제");   // 다시 해도 같은 오류
            }
            return null;
        }).when(indexAdapter).upsert(anyList());

        send(String.valueOf(productId), "ProductChangedEvent");
        send(String.valueOf(otherProductId), "ProductChangedEvent");
        await().atMost(WAIT).until(() -> indexedIds().contains(otherProductId));

        send(String.valueOf(otherProductId), "ProductChangedEvent");   // 컨슈머가 멈추지 않고 다음 메시지도 처리한다
        await().atMost(WAIT).until(() -> indexedIds().stream().filter(otherProductId::equals).count() >= 2);
        assertThat(indexedIds()).doesNotContain(productId);
    }

    /** 성공한 upsert에 들어간 상품 ID (예외를 던진 호출은 빼려고 한 상품씩 다시 한 호출만 센다) */
    private List<Long> indexedIds() {
        return mockingDetails(indexAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upsert"))
                .map(invocation -> invocation.<List<ProductSearchDocument>>getArgument(0))
                .filter(documents -> documents.stream().noneMatch(doc -> doc.productId() == productId))
                .flatMap(documents -> documents.stream().map(ProductSearchDocument::productId))
                .toList();
    }

    private Product saveProduct(String name) {
        return productRepository.save(Product.create(
                sellerId, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), name, "설명", 10_000L));
    }

    private ProductOption saveOption(Product product) {
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        optionStockRepository.save(OptionStock.init(option, 10));
        return option;
    }

    @Test
    @DisplayName("행동 지표가 바뀌는 이벤트(찜, 판매 지표, review.events의 리뷰)도 그 상품을 다시 색인한다")
    void indexesOnBehaviorEvents() {
        doNothing().when(indexAdapter).upsert(anyList());

        send(String.valueOf(productId), "WishAddedEvent");
        send("review.events", String.valueOf(otherProductId), "ReviewCreatedEvent");

        await().atMost(WAIT).until(() -> upsertedIds().containsAll(List.of(productId, otherProductId)));
    }

    @Test
    @DisplayName("상호 변경 이벤트(seller.events, 키 판매자 ID)를 받으면 봉투에서 판매자 ID를 읽어 그 판매자의 판매 중 상품만 다시 색인한다")
    void reindexSellerProductsOnBusinessNameChange() {
        doNothing().when(indexAdapter).upsert(anyList());
        Long[] otherSellerProductId = new Long[1];
        Long[] otherSellerOptionId = new Long[1];
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(sellerId + 1,
                    categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "다른 판매자 크림", "설명", 10_000L));
            otherSellerProductId[0] = product.getId();
            otherSellerOptionId[0] = saveOption(product).getId();
        });
        try {
            EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(new SellerBusinessNameChangedEvent(sellerId, "새 상호"));
            OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(envelope.payload()));
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    "seller.events", String.valueOf(sellerId), eventMessages.toJson(outbox));   // 릴레이와 같은 형식
            record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).join();

            await().atMost(WAIT).until(() -> upsertedIds().containsAll(List.of(productId, otherProductId)));
            assertThat(upsertedIds()).doesNotContain(otherSellerProductId[0], sellerId);   // 다른 판매자 상품, 키(판매자 ID)를 상품 ID로 쓰지 않는다
        } finally {
            transactionTemplate.executeWithoutResult(status -> {
                productOptionRepository.deleteById(otherSellerOptionId[0]);
                productRepository.deleteById(otherSellerProductId[0]);
            });
        }
    }

    @Test
    @DisplayName("회원 서비스가 실패해도 컨슈머는 멈추지 않고 상품을 상호명 없이 색인한다 (상호명은 나중에 채운다)")
    void memberFailureStillIndexes() {
        doNothing().when(indexAdapter).upsert(anyList());
        given(memberApi.getSellers(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        send(String.valueOf(productId), "ProductChangedEvent");

        await().atMost(WAIT).until(() -> upsertedIds().contains(productId));
        assertThat(upsertedDocuments()).filteredOn(doc -> doc.productId() == productId)
                .allSatisfy(doc -> assertThat(doc.businessName()).isNull());
    }

    private List<ProductSearchDocument> upsertedDocuments() {
        return mockingDetails(indexAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upsert"))
                .flatMap(invocation -> invocation.<List<ProductSearchDocument>>getArgument(0).stream())
                .toList();
    }

    /** upsert에 들어간 모든 상품 ID */
    private List<Long> upsertedIds() {
        return mockingDetails(indexAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upsert"))
                .flatMap(invocation -> invocation.<List<ProductSearchDocument>>getArgument(0).stream())
                .map(ProductSearchDocument::productId)
                .toList();
    }

    private long upsertCalls() {
        return mockingDetails(indexAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upsert"))
                .count();
    }

    /** 릴레이와 같은 키·헤더로 보낸다. 검색 색인 컨슈머는 payload를 읽지 않으므로 본문은 비워 둔다 */
    private void send(String key, String eventType) {
        send("product.events", key, eventType);
    }

    private void send(String topic, String key, String eventType) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, "{}");
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }
}
