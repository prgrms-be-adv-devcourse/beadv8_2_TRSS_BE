package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "상품", description = "상품 검색·상세 (로그인 불필요)")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ApiV1ProductController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "상품 검색",
            description = "판매 중이고 사용 중인 카테고리의 상품을 검색합니다. size는 최대 50입니다.")
    @GetMapping
    public RsData<PageResponse<ProductSummaryDto>> searchProducts(
            @Parameter(description = "상품명 일부 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @Parameter(description = "대분류 ID면 그 아래 소분류 전체, 소분류 ID면 그 소분류만")
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "LATEST") ProductSort sort,
            @Parameter(description = "true면 품절 상품 제외")
            @RequestParam(defaultValue = "true") boolean excludeSoldOut,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "상품 목록을 조회했습니다.",
                productFacade.searchProducts(keyword, categoryId, sort, excludeSoldOut, pageable));
    }

    @Operation(
            summary = "상품 상세",
            description = "판매 중지·삭제된 상품과 사용 중지된 카테고리의 상품은 404입니다. 재고가 5개 이하인 옵션만 남은 수량을 줍니다.")
    @GetMapping("/{productId}")
    public RsData<ProductDetailDto> getProductDetail(@PathVariable Long productId) {
        return RsData.of("200-1", "상품을 조회했습니다.", productFacade.getProductDetail(productId));
    }
}
