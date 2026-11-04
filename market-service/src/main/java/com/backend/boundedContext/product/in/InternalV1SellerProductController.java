package com.backend.boundedContext.product.in;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.product.in.dto.SellerProductStopInternalRequest;
import com.backend.global.rsData.RsData;
import com.backend.shared.product.out.ProductApi;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 다른 서비스가 호출하는 판매자 상품 내부 API. 규칙은 {@link ProductApi}를 따른다.
 * TODO 서비스 간 인증 방식이 정해지면 보안 설정 추가
 */
@Tag(name = "판매자 상품 내부 API", description = "서비스 간 호출 전용")
@RestController
@RequestMapping("/internal/v1/sellers/{sellerId}/products")
@RequiredArgsConstructor
public class InternalV1SellerProductController {

    private final ProductApi productApi;

    @Operation(summary = "판매자 상품 전체 판매 중지", description = "판매자 철회·제재 시 호출합니다.")
    @PostMapping("/stop")
    public RsData<Void> stopAllBySeller(@PathVariable Long sellerId,
                                        @Valid @RequestBody SellerProductStopInternalRequest request) {
        productApi.stopAllBySeller(sellerId, request.reason());
        return RsData.of("200-1", "판매자 상품을 판매 중지했습니다.");
    }

    @Operation(summary = "판매자 상품 판매 재개", description = "철회 거절·제재 해제 시 호출합니다.")
    @PostMapping("/resume")
    public RsData<Void> resumeAllBySeller(@PathVariable Long sellerId) {
        productApi.resumeAllBySeller(sellerId);
        return RsData.of("200-1", "판매자 상품 판매를 재개했습니다.");
    }
}
