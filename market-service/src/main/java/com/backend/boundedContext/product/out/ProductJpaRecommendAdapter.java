package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.RecommendCandidate;
import com.backend.boundedContext.product.domain.RecommendCandidates;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.backend.boundedContext.product.out.ProductSpecifications.categoryIn;
import static com.backend.boundedContext.product.out.ProductSpecifications.inStock;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusEquals;

/**
 * DB로 개인화 추천 후보를 찾는다 (테스트(engine=jpa)와 ES를 쓸 수 없을 때).
 * 텍스트 유사도와 행동 점수는 검색 색인에만 있어 둘 다 비워 돌려주므로, 순서는 선호 소분류와 가격대로만 정해진다.
 * 선호 소분류의 최근 상품과 대분류의 최근 상품을 따로 찾아 합친다 (대분류만 보면 선호 소분류가 빠질 수 있다).
 * 가격은 ES 문서와 같이 지금 기본가(할인 중이면 할인가)다.
 */
@Repository
@RequiredArgsConstructor
public class ProductJpaRecommendAdapter implements ProductRecommendPort {
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductRepository productRepository;
    private final ProductDiscountRepository productDiscountRepository;

    @Override
    @Transactional(readOnly = true)
    public RecommendCandidates findCandidates(ProductRecommendQuery query) {
        Map<Long, Product> products = new LinkedHashMap<>();
        latest(query, query.preferredCategoryIds()).forEach(p -> products.put(p.getId(), p));
        latest(query, query.categoryIds()).forEach(p -> products.putIfAbsent(p.getId(), p));
        if (products.isEmpty()) return RecommendCandidates.withoutText(List.of());

        Map<Long, Long> salePrices = productDiscountRepository
                .findAllActiveByProductIdIn(products.keySet(), LocalDateTime.now()).stream()
                .collect(Collectors.toMap(d -> d.getProduct().getId(), ProductDiscount::getSalePrice, (a, b) -> a));
        return RecommendCandidates.withoutText(products.values().stream()
                .map(product -> new RecommendCandidate(product.getId(), product.getCategory().getId(),
                        salePrices.getOrDefault(product.getId(), product.getPrice()), 0, Map.of()))
                .toList());
    }

    /** 이 소분류들의 판매 중·재고 있는 최근 상품 (뺄 상품 제외) */
    private List<Product> latest(ProductRecommendQuery query, Collection<Long> categoryIds) {
        if (categoryIds.isEmpty()) return List.of();
        Specification<Product> spec = Specification.allOf(
                statusEquals(ProductSaleStatus.ON_SALE),
                categoryIn(categoryIds),
                inStock(),
                query.excludeIds().isEmpty() ? Specification.unrestricted()
                        : (root, q, cb) -> cb.not(root.get("id").in(query.excludeIds())));
        return productRepository.findAll(spec, PageRequest.of(0, query.preferredSize(), LATEST)).getContent();
    }
}
