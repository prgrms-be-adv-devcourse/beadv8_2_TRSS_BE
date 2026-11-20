package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.domain.ProductRecommendPolicy;
import com.backend.boundedContext.product.domain.RecommendBasis;
import com.backend.boundedContext.product.domain.RecommendSeed;
import com.backend.boundedContext.product.domain.RecommendSeedProduct;
import com.backend.boundedContext.product.domain.RecommendSeeds;
import com.backend.boundedContext.product.domain.RecommendSignal;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRecommendQuery;
import com.backend.boundedContext.product.out.ProductRecommendQuery.SeedText;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductSimilarQuery;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.review.dto.MemberRatingDto;
import com.backend.shared.review.out.ReviewApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 개인화 추천의 기준 상품 읽기. 짧은 읽기 트랜잭션에서 회원 행동과 기준 상품 정보를 꺼내,
 * 후보 조회(ES)는 트랜잭션 밖에서 하게 한다.
 */
@Component
@RequiredArgsConstructor
class ProductRecommendSeedSupport {
    /** 출처마다 읽는 최대 행동 수 (기준 상품은 그중 가중치 상위만 쓴다) */
    private static final int SIGNAL_LIMIT = 50;

    private final ProductRecommendPolicy policy;
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final WishlistRepository wishlistRepository;
    private final OrderApi orderApi;
    private final ReviewApi reviewApi;

    /**
     * 회원 행동(높은 별점·구매·찜·장바구니)에서 기준 상품, 후보 조건, 기준 상품별 섹션을 만든다.
     * 기준 상품이 없으면 seeds가 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public MemberSnapshot readMember(Long memberId, LocalDateTime now) {
        List<RecommendSignal> signals = new ArrayList<>();
        Set<Long> lowRated = new HashSet<>();
        for (MemberRatingDto rating : reviewApi.getMemberRatings(memberId)) {
            if (rating.rating() >= policy.getHighRatingMin()) {
                signals.add(new RecommendSignal(rating.productId(), RecommendBasis.HIGH_RATING, rating.ratedAt()));
            } else if (rating.rating() <= policy.getLowRatingMax()) {
                lowRated.add(rating.productId());
            }
        }
        orderApi.getRecentPurchases(memberId, now.minusDays(policy.getPurchaseDays()), SIGNAL_LIMIT)
                .forEach(p -> signals.add(new RecommendSignal(p.productId(), RecommendBasis.PURCHASE, p.paidAt())));
        wishlistRepository.findRecentByMemberId(memberId, Limit.of(SIGNAL_LIMIT))
                .forEach(w -> signals.add(new RecommendSignal(w.productId(), RecommendBasis.WISH, w.wishedAt())));
        cartSignals(memberId).forEach(signals::add);

        Set<Long> excludeIds = new HashSet<>(lowRated);
        signals.forEach(signal -> excludeIds.add(signal.productId()));

        List<RecommendSeed> seeds = RecommendSeeds.fromSignals(policy, signals, lowRated, now).stream()
                .limit(policy.getSeedCount()).toList();
        List<SectionSeed> sectionSeeds = sectionSeeds(signals, lowRated);

        Set<Long> productIds = new LinkedHashSet<>();
        seeds.forEach(seed -> productIds.add(seed.productId()));
        sectionSeeds.forEach(seed -> productIds.add(seed.productId()));
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        Seeds built = build(seeds, products, excludeIds, childrenByParent);
        List<SectionQuery> sections = sectionSeeds.stream()
                .filter(seed -> products.containsKey(seed.productId()))
                .map(seed -> sectionQuery(seed, products.get(seed.productId()), childrenByParent))
                .flatMap(Optional::stream)
                .toList();
        return new MemberSnapshot(built.seedProducts(), built.query(), sections, excludeIds);
    }

    /** 최근 본 상품(최근 순)에서 기준 상품과 후보 조건을 만든다. 최근 본 상품은 모두 결과에서 뺀다 */
    @Transactional(readOnly = true)
    public RecentSnapshot readRecent(List<Long> recentViewIds) {
        List<RecommendSeed> seeds = RecommendSeeds.fromRecentViews(recentViewIds).stream()
                .limit(policy.getSeedCount()).toList();
        Map<Long, Product> products = productRepository.findAllById(seeds.stream().map(RecommendSeed::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Seeds built = build(seeds, products, new HashSet<>(recentViewIds), new HashMap<>());
        return new RecentSnapshot(built.seedProducts(), built.query());
    }

    /** 구매자에게 보이는 모든 소분류 (인기순으로 대신할 때 검색 범위) */
    @Transactional(readOnly = true)
    public List<Long> allDisplayableCategoryIds() {
        return productSupport.getDisplayableCategoryIds(null);
    }

    /** 상품 이름 (추천 이유·섹션 기준 상품 표시용). 판매가 끝난 상품도 이름은 준다 */
    @Transactional(readOnly = true)
    public Map<Long, String> productNames(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));
    }

    /** 장바구니 옵션을 상품으로 바꾼다. 같은 상품의 옵션이 여러 개면 가장 최근에 담은 것 하나 */
    private List<RecommendSignal> cartSignals(Long memberId) {
        var options = orderApi.getCartOptions(memberId);
        if (options.isEmpty()) return List.of();
        Map<Long, Long> productByOption = productOptionRepository.findAllById(
                        options.stream().map(o -> o.optionId()).toList()).stream()
                .collect(Collectors.toMap(ProductOption::getId, option -> option.getProduct().getId()));
        return options.stream()
                .filter(o -> productByOption.containsKey(o.optionId()))
                .map(o -> new RecommendSignal(productByOption.get(o.optionId()), RecommendBasis.CART, o.addedAt()))
                .toList();
    }

    /**
     * 기준 상품별 섹션의 기준: 최근 구매 하나, 높게 평가한 상품 중 최근 하나, 최근 찜 하나 (겹치면 그 출처의 다음 것).
     * 섹션 수가 더 많으면 같은 순서로 다음 것을 더 고른다
     */
    private List<SectionSeed> sectionSeeds(List<RecommendSignal> signals, Set<Long> lowRated) {
        List<RecommendBasis> order = List.of(RecommendBasis.PURCHASE, RecommendBasis.HIGH_RATING, RecommendBasis.WISH);
        Map<RecommendBasis, List<RecommendSignal>> byBasis = signals.stream()
                .filter(s -> !lowRated.contains(s.productId()))
                .collect(Collectors.groupingBy(RecommendSignal::basis));
        byBasis.values().forEach(list -> list.sort(Comparator.comparing(RecommendSignal::at,
                Comparator.nullsLast(Comparator.reverseOrder()))));

        List<SectionSeed> picked = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (int round = 0; picked.size() < policy.getSectionCount() && round < SIGNAL_LIMIT; round++) {
            boolean any = false;
            for (RecommendBasis basis : order) {
                if (picked.size() >= policy.getSectionCount()) break;
                Optional<RecommendSignal> next = byBasis.getOrDefault(basis, List.of()).stream()
                        .filter(s -> !used.contains(s.productId())).findFirst();
                if (next.isPresent()) {
                    any = true;
                    used.add(next.get().productId());
                    picked.add(new SectionSeed(basis, next.get().productId()));
                }
            }
            if (!any) break;
        }
        return picked;
    }

    /**
     * 대분류의 사용 중인 소분류. 같은 대분류의 기준 상품이 여럿이어도 카테고리를 한 번만 읽도록 이 계산 안에서 기억한다
     */
    private List<Long> displayableChildren(Product product, Map<Long, List<Long>> childrenByParent) {
        return childrenByParent.computeIfAbsent(product.getCategory().getParent().getId(),
                productSupport::getDisplayableCategoryIds);
    }

    private Seeds build(List<RecommendSeed> seeds, Map<Long, Product> products, Set<Long> excludeIds,
                        Map<Long, List<Long>> childrenByParent) {
        List<RecommendSeed> existing = seeds.stream().filter(seed -> products.containsKey(seed.productId())).toList();
        if (existing.isEmpty()) {
            return new Seeds(List.of(), new ProductRecommendQuery(List.of(), List.of(), List.of(), excludeIds, 0, 0));
        }
        Map<Long, ProductPrice> prices = productDiscountSupport.getPrices(
                existing.stream().map(seed -> products.get(seed.productId())).toList());

        List<RecommendSeedProduct> seedProducts = new ArrayList<>();
        List<SeedText> texts = new ArrayList<>();
        Set<Long> categoryIds = new LinkedHashSet<>();
        Set<Long> seedCategoryIds = new LinkedHashSet<>();
        for (RecommendSeed seed : existing) {
            Product product = products.get(seed.productId());
            seedProducts.add(new RecommendSeedProduct(seed, product.getCategory().getId(),
                    prices.get(product.getId()).basePrice()));
            texts.add(new SeedText(product.getId(), ProductSimilarSupport.likeText(product)));
            seedCategoryIds.add(product.getCategory().getId());
            categoryIds.addAll(displayableChildren(product, childrenByParent));
        }
        List<Long> preferred = seedCategoryIds.stream().filter(categoryIds::contains).toList();
        return new Seeds(seedProducts, new ProductRecommendQuery(texts, List.copyOf(categoryIds), preferred, excludeIds,
                policy.getCandidatePerSeed(), policy.getPreferredCandidateSize()));
    }

    /** 기준 상품 하나의 비슷한 상품 조건 (#비슷한 상품과 같은 질의). 그 대분류에 사용 중인 소분류가 없으면 빈 값 */
    private Optional<SectionQuery> sectionQuery(SectionSeed seed, Product product, Map<Long, List<Long>> childrenByParent) {
        List<Long> categoryIds = displayableChildren(product, childrenByParent);
        if (categoryIds.isEmpty()) return Optional.empty();
        ProductSimilarQuery query = new ProductSimilarQuery(product.getId(), ProductSimilarSupport.likeText(product),
                product.getCategory().getId(), categoryIds, policy.getSectionStoreSize() * 2);
        return Optional.of(new SectionQuery(seed.basis(), query, productDiscountSupport.getPrice(product).basePrice()));
    }

    private record Seeds(List<RecommendSeedProduct> seedProducts, ProductRecommendQuery query) {
    }

    private record SectionSeed(RecommendBasis basis, long productId) {
    }

    /**
     * @param basis     기준 상품의 출처
     * @param query     비슷한 상품 조건
     * @param basePrice 기준 상품의 지금 기본가
     */
    record SectionQuery(RecommendBasis basis, ProductSimilarQuery query, long basePrice) {
    }

    /**
     * @param seeds      기준 상품 (가중치 순). 비어 있으면 개인화하지 않는다
     * @param query      후보 조건
     * @param sections   기준 상품별 섹션 조건
     * @param excludeIds 추천 결과에서 뺄 상품
     */
    record MemberSnapshot(List<RecommendSeedProduct> seeds, ProductRecommendQuery query, List<SectionQuery> sections,
                          Set<Long> excludeIds) {
    }

    record RecentSnapshot(List<RecommendSeedProduct> seeds, ProductRecommendQuery query) {
    }
}
