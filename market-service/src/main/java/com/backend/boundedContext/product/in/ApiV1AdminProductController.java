package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.AdminProductSummaryDto;
import com.backend.boundedContext.product.domain.AdminProductDisplayStatus;
import com.backend.boundedContext.product.in.dto.ProductReasonRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 상품", description = "전체 상품 조회, 즉시 판매 중지 (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class ApiV1AdminProductController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "전체 상품 목록",
            description = "삭제된 상품까지 최신 등록순으로 줍니다(sort 값은 무시). 보내지 않은 조건은 적용하지 않습니다. "
                    + "categoryId가 대분류면 그 아래 소분류 상품까지 보여 줍니다. page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<AdminProductSummaryDto>> getProducts(
            @Parameter(description = "판매자 ID (seller.id)")
            @RequestParam(required = false) Long sellerId,
            @Parameter(description = "ON_SALE(재고 있음) · SOLD_OUT(재고 0) · STOPPED(판매 중지) · DELETED(삭제됨)")
            @RequestParam(required = false) AdminProductDisplayStatus status,
            @Parameter(description = "대분류 또는 소분류 ID")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "상품명 일부 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "상품 목록을 조회했습니다.",
                productFacade.getProductsForAdmin(sellerId, status, categoryId, keyword, pageable));
    }

    @Operation(
            summary = "즉시 판매 중지",
            description = "사유를 적고 상품 판매를 바로 멈춥니다(해제 없음). 판매 중(품절 포함)인 상품만 중지할 수 있고, "
                    + "이미 판매 중지·삭제된 상품은 409-PRODUCT_NOT_ON_SALE입니다. "
                    + "결제 대기 중인 주문은 취소되고, 심사 중인 판매 중지 요청은 닫힙니다.")
    @PostMapping("/{productId}/stop")
    public RsData<Void> stopProduct(@PathVariable Long productId, @Valid @RequestBody ProductReasonRequest request) {
        productFacade.stopProductByAdmin(productId, request.reason());
        return RsData.of("200-1", "상품 판매를 중지했습니다.");
    }
}
