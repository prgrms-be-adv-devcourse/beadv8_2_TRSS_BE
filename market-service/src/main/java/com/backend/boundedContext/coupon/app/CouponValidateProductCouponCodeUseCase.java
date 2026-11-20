package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.ProductCouponValidationDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponOrderPort;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.CartLineDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상품 쿠폰 코드 검증 (주문서에서 코드 입력 시). 예약은 하지 않는다(주문 생성 때 다시 검증하고 예약한다).
 *
 * <p>확인 순서: 코드가 있는지(404-COUPON_CODE_NOT_FOUND) → 사용 기간 안이고 중지되지 않았는지(409-COUPON_NOT_ACTIVE)
 * → 남은 수량(409-COUPON_SOLD_OUT) → 이미 사용, 예약했는지(409-COUPON_ALREADY_USED)
 * → 구매자가 지정한 장바구니 항목이 판매 중인 대상 상품인지(400-COUPON_NOT_APPLICABLE).
 * 코드는 앞뒤 공백을 지우고 대문자로 바꿔 찾는다.
 *
 * <p>상품 쿠폰은 한 주문에 1개, 주문 항목 1개에 붙는다. 대상 상품의 항목(옵션)이 여러 개여도 할인을 붙일 항목은 구매자가 정한다
 * (보낸 순서로 고르면 같은 장바구니라도 예상 할인액이 달라진다). 주문 생성 때도 같은 항목에 붙여 다시 계산한다.
 */
@Component
@RequiredArgsConstructor
public class CouponValidateProductCouponCodeUseCase {

    private final ProductCouponRepository productCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponOrderPort couponOrderPort;

    /** @param cartItemId 할인을 붙일 장바구니 항목 (구매자가 지정) */
    public ProductCouponValidationDto validate(Long memberId, String code, Long cartItemId) {
        LocalDateTime now = LocalDateTime.now();
        ProductCoupon productCoupon = productCouponRepository.findWithCouponByCode(CouponCodeGenerator.normalize(code))
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_CODE_NOT_FOUND));
        Coupon coupon = productCoupon.getCoupon();

        if (!coupon.isUsableAt(now)) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_ACTIVE);
        }
        if (coupon.isSoldOut()) {
            throw new DomainException(CouponErrorCode.COUPON_SOLD_OUT);
        }
        if (couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(coupon.getId(), memberId, CouponUsage.OCCUPIED_STATUSES)) {
            throw new DomainException(CouponErrorCode.COUPON_ALREADY_USED);
        }

        // 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND
        CartLineDto target = couponOrderPort.getCartLines(memberId, List.of(cartItemId)).getFirst();
        if (!target.saleable() || !productCoupon.getProductId().equals(target.productId())) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_APPLICABLE);
        }

        long lineAmount = target.lineAmount();
        return new ProductCouponValidationDto(coupon.getId(), productCoupon.getCode(), coupon.getName(),
                target.cartItemId(), productCoupon.getProductId(), lineAmount,
                productCoupon.discountFor(lineAmount), coupon.getValidUntil());
    }
}
