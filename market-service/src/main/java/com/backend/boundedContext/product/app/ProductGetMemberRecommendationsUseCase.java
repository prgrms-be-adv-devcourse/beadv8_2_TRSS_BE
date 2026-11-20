package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.MemberRecommendationsDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.app.dto.RecommendationReasonDto;
import com.backend.boundedContext.product.app.dto.RecommendationSectionDto;
import com.backend.boundedContext.product.app.dto.RecommendedProductDto;
import com.backend.boundedContext.product.domain.MemberRecommendation;
import com.backend.boundedContext.product.domain.ProductRecommendPolicy;
import com.backend.boundedContext.product.out.ProductRecommendStore;
import com.backend.boundedContext.product.out.RecommendStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductGetMemberRecommendationsUseCase {
    /** 회원님을 위한 추천 최대 개수 */
    static final int MAX_SIZE = 50;
    /** 섹션 하나에 보여 줄 상품 수 */
    static final int SECTION_SIZE = 10;

    private final ProductRecommendPolicy policy;
    private final ProductRecommendSupport productRecommendSupport;
    private final ProductRecommendRefresher productRecommendRefresher;
    private final ProductRecommendStore productRecommendStore;

    /**
     * 회원 추천. 미리 계산해 저장한 결과를 읽고, 화면 값은 DB의 지금 값으로 채운다 (판매 중지·품절은 뺀다).
     * <ul>
     *   <li>로그인하지 않았으면(memberId null) 인기순, personalized=false</li>
     *   <li>저장한 결과가 없으면(첫 요청, TTL 만료) 그 자리에서 계산해 저장한다</li>
     *   <li>저장한 결과가 staleMinutes보다 오래됐으면 그대로 돌려주고 뒤에서 다시 계산한다
     *   (이벤트가 없는 장바구니·주문 취소를 맞춘다)</li>
     *   <li>행동 이력이 없으면 인기순, personalized=false</li>
     *   <li>저장소(Redis)가 응답하지 않으면 인기순, personalized=false (요청마다 계산하지 않는다)</li>
     * </ul>
     * 같은 상품은 회원님을 위한 추천이나 앞 섹션에 한 번만 나오고, 한 목록에 같은 판매자 상품은 sellerMax개까지다.
     */
    public MemberRecommendationsDto getRecommendations(Long memberId, int size) {
        int limit = Math.clamp(size, 1, MAX_SIZE);
        if (memberId == null) return popular(limit);

        MemberRecommendation recommendation;
        try {
            recommendation = productRecommendStore.find(memberId).orElse(null);
        } catch (RecommendStoreUnavailableException e) {
            // 저장소 장애 동안 요청마다 전체 계산(ES 질의 여러 번)을 하지 않고 인기순으로 대신한다
            log.warn("추천 저장소가 응답하지 않아 인기순으로 대신합니다. memberId={}", memberId);
            return popular(limit);
        }
        if (recommendation == null) {
            recommendation = productRecommendSupport.compute(memberId);
        } else if (recommendation.generatedAt().isBefore(LocalDateTime.now().minusMinutes(policy.getStaleMinutes()))) {
            productRecommendRefresher.refreshLater(memberId);
        }
        if (!recommendation.personalized()) return popular(limit);
        return assemble(recommendation, limit);
    }

    private MemberRecommendationsDto assemble(MemberRecommendation recommendation, int limit) {
        Set<Long> ids = new LinkedHashSet<>();
        recommendation.forYou().forEach(item -> ids.add(item.productId()));
        recommendation.sections().forEach(section -> ids.addAll(section.productIds()));
        Map<Long, ProductSummaryDto> summaries = productRecommendSupport.summaries(ids);

        Set<Long> nameIds = new HashSet<>();
        recommendation.forYou().stream().map(MemberRecommendation.Item::reasonProductId).filter(Objects::nonNull).forEach(nameIds::add);
        recommendation.sections().forEach(section -> nameIds.add(section.seedProductId()));
        Map<Long, String> names = productRecommendSupport.productNames(nameIds);
        // 섹션 제목 옆 썸네일. 기준 상품은 이미 샀거나 찜한 상품이라 화면 값(summaries)에 없어서 따로 읽는다
        Map<Long, String> seedThumbnails = productRecommendSupport.thumbnailUrls(
                recommendation.sections().stream().map(MemberRecommendation.Section::seedProductId).toList());

        Set<Long> used = new HashSet<>();
        Map<Long, MemberRecommendation.Item> items = recommendation.forYou().stream()
                .collect(Collectors.toMap(MemberRecommendation.Item::productId, Function.identity(), (a, b) -> a));
        List<RecommendedProductDto> forYou = productRecommendSupport.pick(
                        recommendation.forYou().stream().map(MemberRecommendation.Item::productId).toList(), summaries, limit, used)
                .stream()
                .map(summary -> new RecommendedProductDto(summary, reason(items.get(summary.productId()), names)))
                .toList();

        List<RecommendationSectionDto> sections = new ArrayList<>();
        for (MemberRecommendation.Section section : recommendation.sections()) {
            List<ProductSummaryDto> picked = productRecommendSupport.pick(section.productIds(), summaries, SECTION_SIZE, used);
            if (!picked.isEmpty()) {
                sections.add(new RecommendationSectionDto(section.basis(), section.seedProductId(),
                        names.get(section.seedProductId()), seedThumbnails.get(section.seedProductId()), picked));
            }
        }
        return new MemberRecommendationsDto(true, forYou, sections);
    }

    private static RecommendationReasonDto reason(MemberRecommendation.Item item, Map<Long, String> names) {
        if (item == null || item.reasonProductId() == null || item.reasonBasis() == null) return null;
        return new RecommendationReasonDto(item.reasonBasis(), item.reasonProductId(), names.get(item.reasonProductId()));
    }

    /** 인기순. 걸러지는 상품(판매자당 최대 수 등)을 생각해 네 배까지 후보로 읽는다 */
    private MemberRecommendationsDto popular(int limit) {
        List<RecommendedProductDto> items = productRecommendSupport.pickInChunks(productRecommendSupport.popular(limit * 4),
                        limit, new HashSet<>()).stream()
                .map(summary -> new RecommendedProductDto(summary, null))
                .toList();
        return new MemberRecommendationsDto(false, items, List.of());
    }
}
