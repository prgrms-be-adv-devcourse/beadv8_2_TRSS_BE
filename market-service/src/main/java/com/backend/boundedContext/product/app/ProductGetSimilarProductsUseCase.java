package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.ProductSimilarSupport.SimilarBase;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductSimilarPolicy;
import com.backend.boundedContext.product.domain.ProductSimilarityScore;
import com.backend.boundedContext.product.out.ProductSimilarPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductGetSimilarProductsUseCase {
    /** 한 번에 줄 수 있는 최대 개수 */
    static final int MAX_SIZE = 20;

    private final ProductSimilarSupport productSimilarSupport;
    private final ProductSummarySupport productSummarySupport;
    private final ProductSimilarPort productSimilarPort;
    private final ProductSimilarPolicy policy;

    /**
     * 상품 상세의 "비슷한 상품". 속성(상품명·설명의 형태소, 소분류, 가격대)과 행동 점수(찜·별점·판매량)로 고른다.
     * 후보는 기준 상품과 같은 대분류에서만 찾고, 기준 상품·품절·지금 보여 줄 수 없는 상품은 뺀다.
     * 기준 상품이 없거나 보여 줄 수 없으면 404. size는 1~20으로 맞춘다.
     * <p>
     * 트랜잭션을 잡지 않는다. 기준 상품 읽기와 화면 값 만들기만 각각 짧은 읽기 트랜잭션에서 하고,
     * 후보 조회(ES)는 그 사이 트랜잭션 밖에서 해 ES가 느려도 DB 커넥션을 쥐고 있지 않게 한다.
     */
    public List<ProductSummaryDto> getSimilarProducts(Long productId, int size) {
        int limit = Math.clamp(size, 1, MAX_SIZE);
        SimilarBase base = productSimilarSupport.readBase(productId, policy.getCandidateSize());

        List<Long> ranked = ProductSimilarityScore.rank(policy, productSimilarPort.findCandidates(base.query()),
                base.query().categoryId(), base.basePrice());
        return pick(ranked, limit);
    }

    /**
     * 순위대로 limit개를 고른다. 색인은 늦을 수 있어 DB의 지금 값으로 다시 거르고(판매 중지·품절 등),
     * 빠진 만큼 다음 후보를 limit의 두 배씩 더 읽어 후보가 남아 있는 한 limit개를 채운다.
     */
    private List<ProductSummaryDto> pick(List<Long> ranked, int limit) {
        List<ProductSummaryDto> picked = new ArrayList<>();
        int chunk = limit * 2;
        for (int from = 0; from < ranked.size() && picked.size() < limit; from += chunk) {
            List<Long> ids = ranked.subList(from, Math.min(ranked.size(), from + chunk));
            Map<Long, ProductSummaryDto> summaries = productSummarySupport.toSummaries(ids);
            for (Long id : ids) {
                ProductSummaryDto summary = summaries.get(id);
                if (summary != null && !summary.soldOut() && picked.size() < limit) {
                    picked.add(summary);
                }
            }
        }
        return picked;
    }
}
