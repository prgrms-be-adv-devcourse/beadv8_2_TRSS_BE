package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.ProductCouponDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueResultDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.in.dto.ProductCouponIssueRequest;
import com.backend.boundedContext.coupon.in.dto.ProductCouponUpdateRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "판매자 쿠폰", description = "상품 쿠폰 발행, 조회, 수정, 중지 (승인된 판매자)")
@RestController
@RequestMapping("/api/v1/seller/coupons")
@RequiredArgsConstructor
public class ApiV1SellerCouponController {

    private final CouponFacade couponFacade;

    @Operation(
            summary = "상품 쿠폰 발행",
            description = "진행 중인 협약의 인플루언서가 pick한 내 판매 중 상품 1개에 쿠폰을 발행하고 코드를 만듭니다(대문자와 숫자 10자리). "
                    + "할인은 판매자가 부담합니다. 유효 기간은 시작 포함, 종료 미포함입니다.")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED / 400-COUPON_PERIOD_INVALID / 400-PRODUCT_NOT_OWNED / 400-PRODUCT_NOT_PICKED")
    @ApiResponse(responseCode = "403", description = "403-SELLER_NOT_APPROVED / 403-FORBIDDEN 내 협약이 아님")
    @ApiResponse(responseCode = "404", description = "404-PARTNERSHIP_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-PARTNERSHIP_NOT_ACTIVE / 409-PRODUCT_NOT_ON_SALE / 409-COUPON_PERIOD_OVERLAPPED")
    @PostMapping
    public RsData<ProductCouponIssueResultDto> issue(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ProductCouponIssueRequest request
    ) {
        return RsData.of("200-1", "상품 쿠폰을 발행했습니다.",
                couponFacade.issueProductCoupon(authMember.memberId(), request.toCommand()));
    }

    @Operation(summary = "내 쿠폰 목록", description = "최신 발행순. 인플루언서, 상품, 사용 수 포함. page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<ProductCouponDto>> getCoupons(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "SCHEDULED(시작 전), ACTIVE(사용 가능), ENDED(종료), STOPPED(중지). 없으면 전체")
            @RequestParam(required = false) CouponDisplayStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "쿠폰 목록을 조회했습니다.",
                couponFacade.getSellerCoupons(authMember.memberId(), status, pageable));
    }

    @Operation(summary = "쿠폰 상세", description = "코드, 대상 상품, 인플루언서, 사용 수, 할인 총액")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 내 쿠폰이 아님")
    @ApiResponse(responseCode = "404", description = "404-COUPON_NOT_FOUND")
    @GetMapping("/{couponId}")
    public RsData<ProductCouponDto> getCoupon(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long couponId) {
        return RsData.of("200-1", "쿠폰 상세를 조회했습니다.", couponFacade.getSellerCoupon(authMember.memberId(), couponId));
    }

    @Operation(
            summary = "쿠폰 수정",
            description = "보낸 항목만 바꿉니다. 시작 전에는 모든 항목을, 시작 후에는 validUntil 연장만 할 수 있습니다.")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED / 400-COUPON_PERIOD_INVALID")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 내 쿠폰이 아님")
    @ApiResponse(responseCode = "404", description = "404-COUPON_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-COUPON_NOT_EDITABLE / 409-COUPON_PERIOD_OVERLAPPED")
    @PatchMapping("/{couponId}")
    public RsData<Void> update(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long couponId,
            @Valid @RequestBody ProductCouponUpdateRequest request
    ) {
        couponFacade.updateProductCoupon(authMember.memberId(), couponId, request.toCommand());
        return RsData.of("200-1", "쿠폰을 수정했습니다.");
    }

    @Operation(summary = "쿠폰 중지", description = "쿠폰을 바로 중지합니다(해제 없음). 이미 주문에 예약된 쿠폰은 그대로 처리됩니다.")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 내 쿠폰이 아님")
    @ApiResponse(responseCode = "404", description = "404-COUPON_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-COUPON_STATE_INVALID 이미 중지됐거나 끝난 쿠폰")
    @PostMapping("/{couponId}/stop")
    public RsData<Void> stop(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long couponId) {
        couponFacade.stopCouponBySeller(authMember.memberId(), couponId);
        return RsData.of("200-1", "쿠폰을 중지했습니다.");
    }
}
