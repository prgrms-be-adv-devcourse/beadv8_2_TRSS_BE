package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.ProductRecommendSeedSupport.MemberSnapshot;
import com.backend.boundedContext.product.app.ProductRecommendSeedSupport.RecentSnapshot;
import com.backend.boundedContext.product.app.ProductRecommendSeedSupport.SectionQuery;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.MemberRecommendation;
import com.backend.boundedContext.product.domain.ProductRecommendPolicy;
import com.backend.boundedContext.product.domain.ProductSimilarCandidates;
import com.backend.boundedContext.product.domain.ProductSimilarPolicy;
import com.backend.boundedContext.product.domain.ProductSimilarityScore;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.domain.RecommendBasis;
import com.backend.boundedContext.product.domain.RecommendCandidates;
import com.backend.boundedContext.product.domain.RecommendScore;
import com.backend.boundedContext.product.domain.RecommendSeedProduct;
import com.backend.boundedContext.product.out.ProductRecommendPort;
import com.backend.boundedContext.product.out.ProductRecommendStore;
import com.backend.boundedContext.product.out.ProductSearchPort;
import com.backend.boundedContext.product.out.ProductSearchQuery;
import com.backend.boundedContext.product.out.ProductSimilarPort;
import com.backend.boundedContext.product.out.RecommendStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 개인화 추천 계산. 트랜잭션을 잡지 않는다: 기준 상품 읽기와 화면 값 만들기는 각각 짧은 읽기 트랜잭션에서 하고,
 * 후보 조회(ES)는 그 사이 트랜잭션 밖에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ProductRecommendSupport {
    private final ProductRecommendPolicy policy;
    private final ProductSimilarPolicy similarPolicy;
    private final ProductRecommendSeedSupport seedSupport;
    private final ProductSummarySupport productSummarySupport;
    private final ProductImageSupport productImageSupport;
    private final ProductRecommendPort productRecommendPort;
    private final ProductSimilarPort productSimilarPort;
    private final ProductSearchPort productSearchPort;
    private final ProductRecommendStore productRecommendStore;

    /**
     * 회원 추천을 계산해 저장하고 돌려준다. 회원님을 위한 추천과 기준 상품별 섹션을 상품 ID와 이유로만 남긴다.
     * 행동 이력이 없으면 개인화하지 않았다고 저장한다 (읽을 때 인기순).
     * 무엇을 추천했는지 로그로 남긴다 (추천한 상품 중 찜·구매로 이어진 비율을 셀 때 쓴다).
     */
    public MemberRecommendation compute(Long memberId) {
        LocalDateTime now = LocalDateTime.now();
        MemberSnapshot snapshot = seedSupport.readMember(memberId, now);
        if (snapshot.seeds().isEmpty()) {
            return save(memberId, MemberRecommendation.notPersonalized(now));
        }
        RecommendCandidates candidates = productRecommendPort.findCandidates(snapshot.query());
        List<ProductSimilarCandidates> sectionCandidates = productSimilarPort.findAllCandidates(
                snapshot.sections().stream().map(SectionQuery::query).toList());
        MemberRecommendation recommendation = new MemberRecommendation(true, now,
                forYou(snapshot, candidates), sections(snapshot, sectionCandidates));

        // ES를 쓸 수 없어 DB 후보(텍스트·행동 점수 없음)로 계산했으면 저장하지 않는다.
        // 저장하면 잘 맞던 이전 결과를 덮고, 생성 시각이 새것이라 한동안 다시 계산되지도 않는다
        if (candidates.degraded() || sectionCandidates.stream().anyMatch(ProductSimilarCandidates::degraded)) {
            log.warn("ES를 쓸 수 없어 계산한 추천을 저장하지 않습니다. 이전 결과가 있으면 그것을 씁니다. memberId={}", memberId);
            return previous(memberId).orElse(recommendation);
        }
        return save(memberId, recommendation);
    }

    /** 저장한 이전 결과. 저장소가 응답하지 않으면 없는 것으로 본다 */
    private Optional<MemberRecommendation> previous(Long memberId) {
        try {
            return productRecommendStore.find(memberId);
        } catch (RecommendStoreUnavailableException e) {
            return Optional.empty();
        }
    }

    private MemberRecommendation save(Long memberId, MemberRecommendation recommendation) {
        productRecommendStore.save(memberId, recommendation, Duration.ofDays(policy.getTtlDays()));
        log.info("개인화 추천을 계산했습니다. memberId={}, personalized={}, forYou={}, sections={}", memberId,
                recommendation.personalized(),
                recommendation.forYou().stream().map(MemberRecommendation.Item::productId).toList(),
                recommendation.sections().stream()
                        .map(section -> section.seedProductId() + "→" + section.productIds()).toList());
        return recommendation;
    }

    /** 최근 본 상품으로 추천한 상품 ID (점수 순). 기준 상품이 없으면 빈 목록 */
    public List<Long> rankRecent(List<Long> recentViewIds) {
        RecentSnapshot snapshot = seedSupport.readRecent(recentViewIds);
        if (snapshot.seeds().isEmpty()) return List.of();
        return RecommendScore.rank(policy, snapshot.seeds(), productRecommendPort.findCandidates(snapshot.query())).stream()
                .map(RecommendScore.Ranked::productId)
                .filter(id -> !snapshot.query().excludeIds().contains(id))
                .toList();
    }

    /** 인기순(행동 점수) 상품 ID. 품절 제외 */
    public List<Long> popular(int size) {
        List<Long> categoryIds = seedSupport.allDisplayableCategoryIds();
        if (categoryIds.isEmpty()) return List.of();
        return productSearchPort.searchProductIds(
                new ProductSearchQuery(null, categoryIds, true, false, ProductSort.POPULAR), PageRequest.of(0, size)).getContent();
    }

    /** 화면 값 (DB의 지금 값으로 다시 거른다) */
    public Map<Long, ProductSummaryDto> summaries(Collection<Long> productIds) {
        return productSummarySupport.toSummaries(List.copyOf(productIds));
    }

    public Map<Long, String> productNames(Collection<Long> productIds) {
        return seedSupport.productNames(productIds);
    }

    /** 상품 ID → 대표 이미지 URL. 대표 이미지가 없는 상품은 키가 없다 */
    public Map<Long, String> thumbnailUrls(Collection<Long> productIds) {
        return productImageSupport.getThumbnailUrls(productIds);
    }

    private List<MemberRecommendation.Item> forYou(MemberSnapshot snapshot, RecommendCandidates candidates) {
        Map<Long, RecommendBasis> basisBySeed = snapshot.seeds().stream()
                .collect(Collectors.toMap(RecommendSeedProduct::productId, seed -> seed.seed().basis()));
        return RecommendScore.rank(policy, snapshot.seeds(), candidates).stream()
                .filter(ranked -> !snapshot.excludeIds().contains(ranked.productId()))
                .limit(policy.getForYouStoreSize())
                .map(ranked -> new MemberRecommendation.Item(ranked.productId(),
                        ranked.reasonSeedId() == null ? null : basisBySeed.get(ranked.reasonSeedId()),
                        ranked.reasonSeedId()))
                .toList();
    }

    private List<MemberRecommendation.Section> sections(MemberSnapshot snapshot, List<ProductSimilarCandidates> candidates) {
        List<MemberRecommendation.Section> sections = new ArrayList<>();
        for (int i = 0; i < snapshot.sections().size(); i++) {
            SectionQuery section = snapshot.sections().get(i);
            List<Long> ids = ProductSimilarityScore.rank(similarPolicy, candidates.get(i),
                            section.query().categoryId(), section.basePrice()).stream()
                    .filter(id -> !snapshot.excludeIds().contains(id))
                    .limit(policy.getSectionStoreSize())
                    .toList();
            if (!ids.isEmpty()) {
                sections.add(new MemberRecommendation.Section(section.basis(), section.query().productId(), ids));
            }
        }
        return sections;
    }

    /**
     * 순서대로 고른다: 화면에 보일 수 없거나(판매 중지 등) 품절인 상품, 이미 다른 목록에 나온 상품을 빼고,
     * 한 판매자 상품은 최대 sellerMax개까지. 고른 상품은 used에 더한다.
     */
    List<ProductSummaryDto> pick(List<Long> orderedIds, Map<Long, ProductSummaryDto> summaries, int limit, Set<Long> used) {
        List<ProductSummaryDto> picked = new ArrayList<>();
        pickInto(orderedIds, summaries, limit, used, new HashMap<>(), picked);
        return picked;
    }

    /**
     * 순위대로 limit개를 고른다. 화면 값은 limit의 두 배씩 나눠 만들고, 걸러진 만큼 다음 후보를 이어서 읽어
     * 후보가 남아 있는 한 limit개를 채운다 (판매자당 최대 수는 묶음을 넘어 이어서 센다).
     */
    List<ProductSummaryDto> pickInChunks(List<Long> rankedIds, int limit, Set<Long> used) {
        List<ProductSummaryDto> picked = new ArrayList<>();
        Map<Long, Integer> perSeller = new HashMap<>();
        int chunk = limit * 2;
        for (int from = 0; from < rankedIds.size() && picked.size() < limit; from += chunk) {
            List<Long> ids = rankedIds.subList(from, Math.min(rankedIds.size(), from + chunk));
            pickInto(ids, summaries(ids), limit, used, perSeller, picked);
        }
        return picked;
    }

    private void pickInto(List<Long> orderedIds, Map<Long, ProductSummaryDto> summaries, int limit, Set<Long> used,
                          Map<Long, Integer> perSeller, List<ProductSummaryDto> picked) {
        for (Long id : orderedIds) {
            if (picked.size() >= limit) return;
            ProductSummaryDto summary = summaries.get(id);
            if (summary == null || summary.soldOut() || used.contains(id)) continue;
            int count = perSeller.getOrDefault(summary.sellerId(), 0);
            if (count >= policy.getSellerMax()) continue;
            perSeller.put(summary.sellerId(), count + 1);
            used.add(id);
            picked.add(summary);
        }
    }
}
