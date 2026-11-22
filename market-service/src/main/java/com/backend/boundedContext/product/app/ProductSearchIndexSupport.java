package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.ProductBehaviorScore;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import com.backend.boundedContext.product.out.search.ProductSearchIndexPort;
import com.backend.global.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 상품 검색 색인 맞추기. DB가 원본이고 색인은 검색용 사본이다.
 * 상품을 바꾼 트랜잭션은 상품 변경 이벤트(ProductChangedEvent)를 outbox에 남기고, 검색 색인 컨슈머가 Kafka로 받아
 * {@link #indexProducts}로 DB의 최신 값을 색인한다.
 * 판매 중인 상품만 색인하고, 판매 중이 아니거나 없는 상품은 색인에서 지운다.
 *
 * <p>색인은 세 단계로 나눈다. DB 값 읽기만 짧은 읽기 트랜잭션에서 하고, 상호명 조회(회원 서비스)와 ES 쓰기는 트랜잭션 밖에서 한다.
 * 색인 컨슈머·복구 스케줄러·전체 재색인이 사용자 요청과 같은 커넥션 풀을 쓰므로, 외부 호출을 기다리는 동안 커넥션을 쥐지 않게 한다.
 * 트랜잭션 안에서 읽은 값은 엔티티가 아닌 {@link IndexSource}로 옮겨, 트랜잭션 밖에서 지연 로딩을 건드릴 수 없게 한다.
 */
@Slf4j
@Service
public class ProductSearchIndexSupport {
    private final ProductRepository productRepository;
    private final ProductDiscountRepository productDiscountRepository;
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductSearchIndexPort productSearchIndexPort;
    private final ProductPolicy productPolicy;
    private final ProductBehaviorSupport productBehaviorSupport;
    private final TransactionTemplate readOnlyTransaction;

    public ProductSearchIndexSupport(ProductRepository productRepository, ProductDiscountRepository productDiscountRepository,
                                     ProductSupport productSupport, ProductDiscountSupport productDiscountSupport,
                                     ProductSearchIndexPort productSearchIndexPort, ProductPolicy productPolicy,
                                     ProductBehaviorSupport productBehaviorSupport, PlatformTransactionManager transactionManager) {
        this.productRepository = productRepository;
        this.productDiscountRepository = productDiscountRepository;
        this.productSupport = productSupport;
        this.productDiscountSupport = productDiscountSupport;
        this.productSearchIndexPort = productSearchIndexPort;
        this.productPolicy = productPolicy;
        this.productBehaviorSupport = productBehaviorSupport;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * 전체 재색인이 실행 중인지. 두 재색인이 겹치면 뒤의 alias 전환이 앞 재색인의 인덱스를 지우고,
     * 앞 재색인의 bulk가 같은 이름으로 매핑 없는 인덱스를 자동 생성한 뒤 alias를 그리로 옮길 수 있어 한 번에 하나만 실행한다 (market 한 대 전제)
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean();

    /**
     * 인덱스가 최신이 아님(alias 없음·매핑 버전 낮음)을 처음 본 시각. 재색인이 끝나면 비운다.
     * 그동안의 색인 쓰기는 거부되거나(alias 없음, 이전 strict 매핑) 이전 인덱스에만 들어가므로,
     * 재색인 뒤 보정을 재색인 시작이 아니라 이 시각부터 해야 그사이 바뀐 상품이 새 인덱스에 빠지지 않는다
     */
    private volatile LocalDateTime outdatedSince;

    /**
     * 재색인 중 컨슈머가 색인한 상품 ID. 그 색인은 이전 인덱스에만 들어가거나 거부되므로 alias를 옮긴 뒤 새 인덱스에 다시 반영한다.
     * 찜·리뷰·판매량처럼 상품의 수정 시각을 바꾸지 않는 변경은 수정 시각으로 찾는 보정에 걸리지 않아서 따로 모은다
     */
    private final Set<Long> indexedWhileRebuilding = ConcurrentHashMap.newKeySet();

    /** 상품들을 DB의 지금 값으로 색인한다. 판매 중이 아니거나 없는 상품은 색인에서 지운다 */
    public void indexProducts(Collection<Long> productIds) {
        indexProducts(productIds, new HashMap<>());
    }

    private void indexProducts(Collection<Long> productIds, Map<Long, String> nameCache) {
        if (productIds.isEmpty() || !productSearchIndexPort.isEnabled()) return;
        if (rebuilding.get()) {
            indexedWhileRebuilding.addAll(productIds);   // 색인이 실패해도(alias 없음) 보정에 넣도록 먼저 모은다
        }

        IndexRead read = readOnlyTransaction.execute(status -> {
            List<Product> onSale = productRepository.findAllById(productIds).stream()
                    .filter(product -> product.getStatus() == ProductSaleStatus.ON_SALE)
                    .toList();
            Set<Long> removed = new HashSet<>(productIds);
            onSale.forEach(product -> removed.remove(product.getId()));
            return new IndexRead(readSources(onSale), removed);
        });

        productSearchIndexPort.upsert(toDocuments(read.sources(), nameCache));
        productSearchIndexPort.delete(read.removed());
    }

    /**
     * 상호명 없이 색인된 문서(회원 서비스가 실패했을 때)를 찾아 다시 색인해 상호명을 채운다. 다시 색인한 상품 수를 돌려준다.
     * 복구 스케줄러가 주기마다 부르고, 한 번에 전체 재색인 묶음 크기만큼 한다. 회원 서비스가 아직 실패하면 다음 주기에 다시 한다
     */
    public int fillMissingBusinessNames() {
        if (!productSearchIndexPort.isEnabled()) return 0;
        List<Long> ids = productSearchIndexPort.findIdsWithoutBusinessName(productPolicy.getSearchIndexBatchSize());
        indexProducts(ids);
        return ids.size();
    }

    /**
     * (from, now] 사이에 할인이 시작했거나 끝난 상품 ID (할인 경계 대상).
     * from은 지난 성공 실행 시각이고, 지난 실행 시각이 없으면(기동 후 첫 실행) now에서 lookback만큼 앞이다.
     * 구간을 겹치지 않게 이어 붙여 같은 경계의 이벤트를 한 번만 발행한다. 겹쳐 읽을 필요가 없는 이유는
     * 할인 시각은 등록할 때 미래로만 정해지고, 등록·수정은 그 자체로 상품 변경 이벤트를 내며,
     * 색인은 이벤트를 처리하는 시점의 DB를 다시 읽기 때문이다 (경계 직전에 커밋된 할인도 그 이벤트가 맞춘다).
     */
    public List<Long> findDiscountBoundaryProductIds(LocalDateTime lastSucceededAt, LocalDateTime now) {
        return productDiscountRepository.findProductIdsByBoundaryBetween(discountBoundaryFrom(lastSucceededAt, now), now);
    }

    LocalDateTime discountBoundaryFrom(LocalDateTime lastSucceededAt, LocalDateTime now) {
        if (lastSucceededAt == null) {
            return now.minusMinutes(productPolicy.getDiscountBoundaryLookbackMinutes());
        }
        return lastSucceededAt;
    }

    /**
     * 검색 인덱스가 최신이 아니면(alias가 없거나 매핑 버전이 코드보다 낮으면) 전체 재색인하고 색인한 상품 수를,
     * 최신이거나 다른 재색인이 실행 중이면 빈 값을 돌려준다 (복구 스케줄러가 주기마다 부른다).
     * 첫 배포·ES 장애·데이터 재구성·매핑 변경 배포 뒤 사람이 재색인을 부르지 않아도 검색이 채워지게 한다.
     */
    public OptionalInt rebuildIndexIfOutdated() {
        if (!productSearchIndexPort.isEnabled() || productSearchIndexPort.isCurrent()) {
            return OptionalInt.empty();
        }
        if (outdatedSince == null) {
            outdatedSince = LocalDateTime.now();   // 재색인이 실패해 다음 주기로 넘어가도 처음 본 시각을 유지한다
        }
        if (!rebuilding.compareAndSet(false, true)) {
            return OptionalInt.empty();   // 다른 재색인이 새 인덱스를 만드는 중이다
        }
        try {
            return OptionalInt.of(rebuild());
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * 판매 중인 상품 전체를 새 인덱스에 색인하고 검색 alias를 옮긴다. 색인한 상품 수를 돌려준다.
     * 매핑을 바꿨거나, 색인이 어긋났을 때 관리자가 부르고, 기동할 때 alias가 없으면 자동으로 부른다.
     * <p>
     * 재색인 중에 바뀐 상품은 이전 인덱스에만 반영되거나(alias가 있을 때), 쓰지 못하고 버려진다(alias가 없을 때).
     * 그래서 alias를 옮긴 뒤 시작 시각 이후 바뀐 상품과 할인 경계를 지난 상품을 새 인덱스에 한 번 더 반영한다.
     * 그 전부터 인덱스가 최신이 아니었으면(outdatedSince) 그 시각부터 반영한다 (그사이 거부된 색인 쓰기를 채운다).
     * 수정 시각이 바뀌지 않는 행동 지표(찜·리뷰·판매량) 변경은 재색인 중 컨슈머가 색인한 상품으로 모아 함께 반영한다.
     * 커밋 지연을 고려해 할인 경계와 같은 겹침(discountBoundaryOverlapSeconds)만큼 앞에서부터 찾는다.
     * 보정이 끝나기 전 잠깐은 이전 값이 보일 수 있지만, 그 뒤의 변경은 alias(새 인덱스)로 들어가므로 어긋나지 않는다.
     */
    public int rebuildIndex() {
        if (!productSearchIndexPort.isEnabled()) {
            throw new DomainException(ProductErrorCode.SEARCH_INDEX_DISABLED);
        }
        if (!rebuilding.compareAndSet(false, true)) {
            throw new DomainException(ProductErrorCode.SEARCH_INDEX_REBUILDING);
        }
        try {
            return rebuild();
        } finally {
            rebuilding.set(false);
        }
    }

    private int rebuild() {
        indexedWhileRebuilding.clear();
        LocalDateTime startedAt = LocalDateTime.now();
        // 판매자 상호명은 재색인 내내 같은 캐시로 판매자마다 한 번만 읽는다 (묶음마다 회원 서비스를 부르지 않게)
        Map<Long, String> nameCache = new HashMap<>();
        String indexName = productSearchIndexPort.createIndex();
        int batchSize = productPolicy.getSearchIndexBatchSize();
        long afterId = 0L;
        int indexed = 0;
        while (true) {
            // 묶음마다 읽기 트랜잭션을 따로 잡아 커넥션을 묶음 동안만 쥔다. 트랜잭션이 끝나면 읽은 상품도 1차 캐시에서 빠지므로
            // 묶음마다 메모리가 쌓이지 않고, 뒤의 보정은 재색인 중 다른 트랜잭션이 커밋한 최신 값을 읽는다
            long cursor = afterId;
            BatchRead batch = readOnlyTransaction.execute(status -> {
                List<Long> ids = productRepository.findIdsByStatusAfter(ProductSaleStatus.ON_SALE, cursor, Limit.of(batchSize));
                return new BatchRead(ids, ids.isEmpty() ? List.of() : readSources(productRepository.findAllById(ids)));
            });
            if (batch.ids().isEmpty()) break;

            List<ProductSearchDocument> documents = toDocuments(batch.sources(), nameCache);
            productSearchIndexPort.bulkIndex(indexName, documents);
            indexed += documents.size();
            afterId = batch.ids().getLast();
            if (batch.ids().size() < batchSize) break;
        }
        productSearchIndexPort.switchAlias(indexName);

        LocalDateTime since = outdatedSince;
        LocalDateTime catchUpFrom = since != null && since.isBefore(startedAt) ? since : startedAt;
        LocalDateTime overlapFrom = catchUpFrom.minusSeconds(productPolicy.getDiscountBoundaryOverlapSeconds());
        Set<Long> changed = readOnlyTransaction.execute(status -> {
            Set<Long> ids = new HashSet<>(productRepository.findIdsChangedSince(overlapFrom));
            ids.addAll(findDiscountBoundaryProductIds(overlapFrom, LocalDateTime.now()));
            return ids;
        });
        changed.addAll(indexedWhileRebuilding);   // alias를 옮긴 뒤의 색인은 새 인덱스로 들어가므로 여기까지 모은 것만 다시 반영한다
        indexedWhileRebuilding.clear();
        indexProducts(changed, nameCache);   // 새 읽기 트랜잭션에서 DB의 최신 값을 읽는다. 상호명은 재색인과 같은 캐시를 쓴다
        outdatedSince = null;
        log.info("상품 검색 전체 재색인을 마쳤습니다. index={}, 상품 {}개, {} 이후 바뀌어 다시 반영한 상품 {}개",
                indexName, indexed, catchUpFrom, changed.size());
        return indexed;
    }

    /**
     * 상품들의 색인 원천 (트랜잭션 안에서 부른다). 품절 여부와 지금 기본가(할인 중이면 할인가)는 검색 화면과 같은 계산을 쓰고,
     * 행동 지표는 지금 DB 값으로 다시 계산한다. 상호명은 비워 두고 {@link #toDocuments}가 트랜잭션 밖에서 채운다
     */
    private List<IndexSource> readSources(List<Product> products) {
        if (products.isEmpty()) return List.of();

        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, List<OptionStock>> stocks = productSupport.getOptionStocksByProductId(ids);
        Map<Long, ProductPrice> prices = productDiscountSupport.getPrices(products);
        Map<Long, ProductBehaviorScore> behaviors = productBehaviorSupport.getScores(ids, LocalDateTime.now());

        return products.stream()
                .map(product -> {
                    ProductPrice price = prices.get(product.getId());
                    ProductBehaviorScore behavior = behaviors.get(product.getId());
                    return new IndexSource(product.getSellerId(), new ProductSearchDocument(
                            product.getId(),
                            product.getName(),
                            product.getDescription(),
                            null,
                            product.getCategory().getId(),
                            product.getStatus().name(),
                            ProductSupport.isSoldOut(stocks.getOrDefault(product.getId(), List.of())),
                            price.basePrice(),
                            discountRateOrZero(price),
                            behavior.wishCount(),
                            behavior.reviewCount(),
                            behavior.ratingScore(),
                            behavior.salesCount(),
                            behavior.behaviorScore(),
                            product.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
                })
                .toList();
    }

    /** 색인 문서 (트랜잭션 밖에서 부른다). nameCache: 이미 읽은 판매자 상호명 (판매자 ID → 상호명, 읽지 못했으면 null) */
    private List<ProductSearchDocument> toDocuments(List<IndexSource> sources, Map<Long, String> nameCache) {
        if (sources.isEmpty()) return List.of();

        Map<Long, String> businessNames = businessNames(sources, nameCache);
        return sources.stream()
                .map(source -> source.document().withBusinessName(businessNames.get(source.sellerId())))
                .toList();
    }

    /**
     * 판매자 상호명. 아직 읽지 않은 판매자만 회원 서비스에서 한 번에 가져와 nameCache에 더한다 (전체 재색인은 묶음 사이에 같은 캐시를 쓴다).
     * <ul>
     *   <li>회원 서비스가 실패하면 상호명 없이(null) 색인한다. 상호명 때문에 판매 중지·품절·가격 반영이 막히지 않게 하고,
     *       빠진 상호명은 {@link #fillMissingBusinessNames}가 나중에 채운다. 같은 계산 안에서는 그 판매자를 다시 부르지 않는다</li>
     *   <li>회원 서비스에 없는 판매자는 빈 문자열로 둔다. 다시 불러도 같으므로 채우기 대상에서 뺀다</li>
     * </ul>
     */
    private Map<Long, String> businessNames(List<IndexSource> sources, Map<Long, String> nameCache) {
        Set<Long> unread = sources.stream().map(IndexSource::sellerId)
                .filter(sellerId -> !nameCache.containsKey(sellerId))
                .collect(Collectors.toSet());
        if (unread.isEmpty()) return nameCache;
        try {
            Map<Long, String> names = productSupport.getBusinessNames(unread);
            unread.forEach(sellerId -> nameCache.put(sellerId, names.getOrDefault(sellerId, "")));
        } catch (RuntimeException e) {
            log.warn("상호명을 가져오지 못해 상호명 없이 색인합니다. 나중에 채웁니다. sellerIds={}", unread, e);
            unread.forEach(sellerId -> nameCache.put(sellerId, null));
        }
        return nameCache;
    }

    /**
     * 트랜잭션 안에서 읽어 둔 색인 원천. 엔티티 대신 값만 담아 트랜잭션 밖에서 지연 로딩을 건드리지 않게 한다
     *
     * @param sellerId 상호명을 찾을 판매자 ID
     * @param document 상호명이 빈 색인 문서
     */
    private record IndexSource(Long sellerId, ProductSearchDocument document) {
    }

    /** 색인할 상품의 원천과 색인에서 지울 상품 ID (판매 중이 아니거나 없는 상품) */
    private record IndexRead(List<IndexSource> sources, Set<Long> removed) {
    }

    /** 전체 재색인 한 묶음의 상품 ID(다음 묶음 커서)와 색인 원천 */
    private record BatchRead(List<Long> ids, List<IndexSource> sources) {
    }

    /** 색인 문서의 할인율. 할인 중이 아니면 0 (할인은 1% 이상만 등록되므로 0이면 할인 없음) */
    private static int discountRateOrZero(ProductPrice price) {
        Integer rate = price.discountRate();
        return rate == null ? 0 : rate;
    }
}
