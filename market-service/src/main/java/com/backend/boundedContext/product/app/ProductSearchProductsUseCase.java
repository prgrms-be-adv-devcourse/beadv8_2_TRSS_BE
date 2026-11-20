package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.ProductSearchPort;
import com.backend.boundedContext.product.out.ProductSearchQuery;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProductSearchProductsUseCase {
    /** 검색 한 페이지의 최대 크기 (전역 최대 100보다 작게 둔다) */
    static final int MAX_PAGE_SIZE = 50;

    private final ProductSupport productSupport;
    private final ProductSummarySupport productSummarySupport;
    private final ProductSearchPort productSearchPort;
    private final ProductPopularKeywordSupport productPopularKeywordSupport;

    /**
     * 판매 중이고 사용 중인 카테고리의 상품을 검색한다.
     * 검색 구현은 상품 ID만 찾고, 화면에 쓸 값(가격·품절 여부·상호명)은 여기서 DB와 회원 컨텍스트로 채운다.
     * 할인가는 조회 시각에 진행 중인 할인으로 채운다. 가격 정렬은 검색 구현이 정한다(ES는 할인가, DB 검색은 정가 기준).
     * sort가 null이면 검색어가 있을 때 관련도순, 없을 때 최신순이다.
     * 검색어가 있는 첫 페이지 검색이 결과를 찾으면 인기 검색어로 센다 (카테고리 필터와 관계없이 센다).
     * 검색 색인이 늦거나 색인 반영에 실패해 지금은 보여 줄 수 없는 상품(판매 중지·삭제, 카테고리 사용 중지, DB에 없음)이 오면
     * DB의 현재 상태로 다시 걸러 결과에서 빼므로, 그 페이지는 size보다 적을 수 있다 (totalElements는 검색 구현의 값 그대로).
     */
    public PageResponse<ProductSummaryDto> searchProducts(String keyword, Long categoryId, ProductSort sort,
                                                          boolean excludeSoldOut, boolean discountedOnly, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

        List<Long> categoryIds = productSupport.getDisplayableCategoryIds(categoryId);   // 없는 카테고리면 404
        if (categoryIds.isEmpty()) {
            return PageResponse.from(Page.empty(page));
        }

        String normalizedKeyword = normalize(keyword);
        ProductSearchQuery query = new ProductSearchQuery(normalizedKeyword, categoryIds, excludeSoldOut, discountedOnly,
                ProductSort.resolve(sort, normalizedKeyword != null));
        Page<Long> productIds = productSearchPort.searchProductIds(query, page);

        Map<Long, ProductSummaryDto> summaries = productSummarySupport.toSummaries(productIds.getContent());
        List<ProductSummaryDto> items = productIds.getContent().stream()
                .map(summaries::get)
                .filter(Objects::nonNull)
                .toList();
        // 화면 값까지 만든 뒤에 센다. 상호명 조회 실패 등으로 응답하지 못한 검색은 세지 않는다
        productPopularKeywordSupport.recordSearch(normalizedKeyword, page.getPageNumber(), productIds.getTotalElements(),
                LocalDateTime.now());
        return new PageResponse<>(items, productIds.getNumber(), productIds.getSize(),
                productIds.getTotalElements(), productIds.getTotalPages());
    }

    /** 앞뒤 공백을 지우고, 비어 있으면 조건 없음(null) */
    private static String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.strip();
    }
}
