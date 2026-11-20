package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.CartCouponDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.out.CouponOrderPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSpecifications;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 구매자의 사용 가능한 장바구니 쿠폰 목록
 *
 * <p>지금 사용 기간 안에 있고 중지되지 않은 장바구니 쿠폰을 종료가 가까운 순으로 보여 준다.
 * 이미 썼거나 예약한 쿠폰, 소진된 쿠폰도 목록에 남기고 used, soldOut으로 알려 준다.
 * cartItemIds를 주면 그 장바구니 항목들의 현재 상품 금액 합계(배송비 제외)로 쿠폰마다 적용될 할인액을 계산한다.
 * 상품 쿠폰 할인은 주문서에서 정해지므로 여기서는 반영하지 않는다(예상 값).
 */
@Component
@RequiredArgsConstructor
public class CouponGetCartCouponsUseCase {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponOrderPort couponOrderPort;

    public CartCouponsDto getCartCoupons(Long memberId, List<Long> cartItemIds) {
        LocalDateTime now = LocalDateTime.now();

        List<Coupon> coupons = couponRepository.findAll(
                CouponSpecifications.typeEquals(CouponType.CART)
                        .and(CouponSpecifications.displayStatusEquals(CouponDisplayStatus.ACTIVE, now)),
                Sort.by(Sort.Order.asc("validUntil"), Sort.Order.desc("id")));

        Set<Long> usedCouponIds = findUsedCouponIds(memberId, coupons);
        Long orderAmount = (cartItemIds == null || cartItemIds.isEmpty())
                ? null
                : couponOrderPort.getCartItemsAmount(memberId, cartItemIds);

        List<CartCouponDto> items = coupons.stream()
                .map(coupon -> CartCouponDto.of(coupon, usedCouponIds.contains(coupon.getId()), orderAmount))
                .toList();

        return new CartCouponsDto(orderAmount, items);
    }

    // 이 회원이 예약했거나 사용한 쿠폰 ID
    private Set<Long> findUsedCouponIds(Long memberId, List<Coupon> coupons) {
        if (coupons.isEmpty()) {
            return Set.of();   // 빈 IN 절 방지
        }

        List<Long> couponIds = coupons.stream().map(Coupon::getId).toList();
        return new HashSet<>(couponUsageRepository.findCouponIdsByMember(memberId, couponIds, CouponUsage.OCCUPIED_STATUSES));
    }
}
