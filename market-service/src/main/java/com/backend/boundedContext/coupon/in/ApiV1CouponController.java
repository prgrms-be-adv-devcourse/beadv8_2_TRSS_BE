package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponValidationDto;
import com.backend.boundedContext.coupon.in.dto.ProductCouponValidateRequest;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "쿠폰", description = "사용 가능한 장바구니 쿠폰 조회, 상품 쿠폰 코드 검증 (구매자)")
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

    @Operation(
            summary = "상품 쿠폰 코드 검증",
            description = "주문서에서 코드와 할인을 붙일 장바구니 항목(cartItemId)을 보내면 예상 할인액을 줍니다. 상품 쿠폰은 한 주문에 1개, 항목 1개에 붙습니다. "
                    + "예약은 하지 않고, 주문 생성 때 다시 검증합니다. "
                    + "코드는 대소문자를 구분하지 않습니다.")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED / 400-COUPON_NOT_APPLICABLE 지정한 항목이 판매 중인 대상 상품이 아님")
    @ApiResponse(responseCode = "404", description = "404-COUPON_CODE_NOT_FOUND / 404-CART_ITEM_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-COUPON_NOT_ACTIVE / 409-COUPON_SOLD_OUT / 409-COUPON_ALREADY_USED")
    @PostMapping("/validate")
    public RsData<ProductCouponValidationDto> validate(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ProductCouponValidateRequest request
    ) {
        return RsData.of("200-1", "쿠폰 코드를 확인했습니다.",
                couponFacade.validateProductCouponCode(authMember.memberId(), request.code(), request.cartItemId()));
    }
}
