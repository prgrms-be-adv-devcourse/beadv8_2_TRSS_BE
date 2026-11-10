package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "쿠폰", description = "사용 가능한 장바구니 쿠폰 조회 (구매자)")
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class ApiV1CouponController {

    private final CouponFacade couponFacade;

    @Operation(
            summary = "사용 가능한 장바구니 쿠폰 목록",
            description = "지금 사용 기간 안에 있고 중지되지 않은 장바구니 쿠폰을 종료가 가까운 순으로 줍니다. "
                    + "이미 썼거나 예약한 쿠폰(used), 소진된 쿠폰(soldOut)도 함께 보여 줍니다. "
                    + "cartItemIds를 주면 그 항목들의 현재 상품 금액 합계(배송비 제외)로 쿠폰마다 적용될 할인액을 계산합니다.")
    @ApiResponse(responseCode = "404", description = "404-CART_ITEM_NOT_FOUND 내 장바구니 항목이 아님")
    @GetMapping("/cart")
    public RsData<CartCouponsDto> getCartCoupons(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "할인액을 계산할 장바구니 항목 ID (쉼표로 구분, 예: 1,2,3)")
            @RequestParam(required = false) List<Long> cartItemIds
    ) {
        return RsData.of("200-1", "장바구니 쿠폰 목록을 조회했습니다.",
                couponFacade.getCartCoupons(authMember.memberId(), cartItemIds));
    }
}
