package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.AdminCouponDetailDto;
import com.backend.boundedContext.coupon.app.dto.AdminCouponSummaryDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.in.dto.CartCouponCreateRequest;
import com.backend.boundedContext.coupon.in.dto.CartCouponCreateResponse;
import com.backend.boundedContext.coupon.in.dto.CouponStopRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 쿠폰", description = "전체 쿠폰 조회, 장바구니 쿠폰 등록, 쿠폰 강제 중지 (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/coupons")
@RequiredArgsConstructor
public class ApiV1AdminCouponController {

    private final CouponFacade couponFacade;

    @Operation(
            summary = "전체 쿠폰 목록",
            description = "최신 등록순으로 줍니다(sort 값은 무시). 보내지 않은 조건은 적용하지 않습니다. "
                    + "status는 조회 시점의 유효 기간과 중지 여부로 계산한 상태입니다. page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<AdminCouponSummaryDto>> getCoupons(
            @Parameter(description = "PRODUCT(상품 쿠폰) 또는 CART(장바구니 쿠폰)")
            @RequestParam(required = false) CouponType type,
            @Parameter(description = "SCHEDULED(시작 전), ACTIVE(사용 가능), ENDED(종료), STOPPED(중지)")
            @RequestParam(required = false) CouponDisplayStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "쿠폰 목록을 조회했습니다.",
                couponFacade.getCouponsForAdmin(type, status, pageable));
    }

    @Operation(
            summary = "쿠폰 상세",
            description = "할인 구간(장바구니 쿠폰), 사용 수와 할인 합계, 사용 내역을 줍니다. "
                    + "사용 내역은 최신순이고 page(0부터), size(기본 20, 최대 100)로 나눠 조회합니다.")
    @ApiResponse(responseCode = "404", description = "404-COUPON_NOT_FOUND")
    @GetMapping("/{couponId}")
    public RsData<AdminCouponDetailDto> getCoupon(
            @PathVariable Long couponId,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "쿠폰 상세를 조회했습니다.",
                couponFacade.getCouponForAdmin(couponId, pageable));
    }

    @Operation(
            summary = "장바구니 쿠폰 등록",
            description = "금액 구간별 정액 할인 쿠폰을 등록합니다. 주문 금액이 넘는 구간 중 가장 높은 구간의 할인액 하나만 적용됩니다. "
                    + "유효 기간은 시작 포함, 종료 미포함이고 종료는 현재 이후여야 합니다.")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED / 400-COUPON_PERIOD_INVALID / 400-COUPON_TIER_INVALID")
    @PostMapping("/cart")
    public RsData<CartCouponCreateResponse> createCartCoupon(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody CartCouponCreateRequest request
    ) {
        Long couponId = couponFacade.createCartCoupon(authMember.memberId(), request.toCommand());
        return RsData.of("200-1", "장바구니 쿠폰을 등록했습니다.", new CartCouponCreateResponse(couponId));
    }

    @Operation(
            summary = "쿠폰 강제 중지",
            description = "사유를 적고 쿠폰을 바로 중지합니다(해제 없음). 이미 주문에 예약된 쿠폰은 그대로 결제와 취소를 따라갑니다.")
    @ApiResponse(responseCode = "404", description = "404-COUPON_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-COUPON_STATE_INVALID 이미 중지됐거나 유효 기간이 끝난 쿠폰")
    @PostMapping("/{couponId}/stop")
    public RsData<Void> stopCoupon(@PathVariable Long couponId, @Valid @RequestBody CouponStopRequest request) {
        couponFacade.stopCouponByAdmin(couponId, request.reason());
        return RsData.of("200-1", "쿠폰을 중지했습니다.");
    }
}
