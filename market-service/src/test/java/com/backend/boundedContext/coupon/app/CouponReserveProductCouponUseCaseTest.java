package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.backend.boundedContext.coupon.CouponFixtures.PRODUCT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponReserveProductCouponUseCaseTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long COUPON_ID = 77L;
    private static final Long ORDER_ID = 500L;
    private static final Long ORDER_ITEM_ID = 1001L;
    private static final String CODE = "ABCDEFGH23";

    @Mock CouponSupport couponSupport;
    @Mock CouponRepository couponRepository;
    @Mock CouponUsageRepository couponUsageRepository;
    @Mock ProductCouponRepository productCouponRepository;

    @InjectMocks CouponReserveProductCouponUseCase useCase;

    private ProductCoupon givenCoupon(Integer totalQuantity) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = CouponFixtures.withId(CouponFixtures.productCouponBase(now.minusDays(1), now.plusDays(1), totalQuantity), COUPON_ID);
        ProductCoupon productCoupon = CouponFixtures.productCoupon(coupon, 5L, CODE,
                new ProductCouponDiscount(DiscountType.RATE, 10, 5_000L));
        given(productCouponRepository.findCouponIdByCode(CODE)).willReturn(Optional.of(COUPON_ID));
        given(couponSupport.getCouponForUpdate(COUPON_ID)).willReturn(coupon);
        given(productCouponRepository.findWithCouponByCouponId(COUPON_ID)).willReturn(Optional.of(productCoupon));
        return productCoupon;
    }

    private void givenNotUsed() {
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(false);
        given(couponUsageRepository.existsByOrderIdAndCouponTypeAndStatusIn(ORDER_ID, CouponType.PRODUCT, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(false);
    }

    @Test
    @DisplayName("코드로 쿠폰 ID를 찾아 먼저 잠근 뒤 확인하고, 주문 항목에 RESERVED 기록을 남기고 할인액(정률 10%)을 돌려준다")
    void reserve() {
        givenCoupon(10);
        givenNotUsed();
        given(couponRepository.decreaseRemainingQuantity(COUPON_ID)).willReturn(1);

        long discount = useCase.reserve(MEMBER_ID, " abcdefgh23 ", ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, LocalDateTime.now());

        assertThat(discount).isEqualTo(4_000);
        InOrder order = inOrder(productCouponRepository, couponSupport);
        order.verify(productCouponRepository).findCouponIdByCode(CODE);
        order.verify(couponSupport).getCouponForUpdate(COUPON_ID);
        order.verify(productCouponRepository).findWithCouponByCouponId(COUPON_ID);

        ArgumentCaptor<CouponUsage> captor = ArgumentCaptor.forClass(CouponUsage.class);
        verify(couponUsageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CouponUsageStatus.RESERVED);
        assertThat(captor.getValue().getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(captor.getValue().getDiscountAmount()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("없는 코드면 쿠폰을 잠그지 않고 404-COUPON_CODE_NOT_FOUND")
    void codeNotFound() {
        given(productCouponRepository.findCouponIdByCode("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, "nope", ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("404-COUPON_CODE_NOT_FOUND");
        verify(couponSupport, never()).getCouponForUpdate(any());
    }

    @Test
    @DisplayName("주문 항목의 상품이 쿠폰 대상 상품이 아니면 400-COUPON_NOT_APPLICABLE")
    void otherProduct() {
        givenCoupon(null);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, 999L, 40_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("잠근 뒤 본 쿠폰이 중지돼 있으면 409-COUPON_NOT_ACTIVE")
    void stopped() {
        ProductCoupon productCoupon = givenCoupon(null);
        productCoupon.getCoupon().stop(CouponStoppedBy.SELLER, null, LocalDateTime.now());

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
    }

    @Test
    @DisplayName("이 주문에 상품 쿠폰이 이미 있으면 409-COUPON_ORDER_LIMIT_EXCEEDED이고 수량을 줄이지 않는다")
    void orderLimit() {
        givenCoupon(10);
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(false);
        given(couponUsageRepository.existsByOrderIdAndCouponTypeAndStatusIn(ORDER_ID, CouponType.PRODUCT, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(true);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_ORDER_LIMIT_EXCEEDED");
        verify(couponRepository, never()).decreaseRemainingQuantity(anyLong());
    }

    @Test
    @DisplayName("이미 사용했으면 409-COUPON_ALREADY_USED, 소진이면 409-COUPON_SOLD_OUT이고 기록을 남기지 않는다")
    void usedOrSoldOut() {
        givenCoupon(10);
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(true, false);
        given(couponUsageRepository.existsByOrderIdAndCouponTypeAndStatusIn(ORDER_ID, CouponType.PRODUCT, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(false);
        given(couponRepository.decreaseRemainingQuantity(COUPON_ID)).willReturn(0);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_ALREADY_USED");
        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_SOLD_OUT");
        verify(couponUsageRepository, never()).saveAndFlush(any());
    }
}
