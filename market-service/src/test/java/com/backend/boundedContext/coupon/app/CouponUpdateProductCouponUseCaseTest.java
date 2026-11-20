package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.ProductCouponUpdateCommand;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.backend.boundedContext.coupon.CouponFixtures.INFLUENCER_ID;
import static com.backend.boundedContext.coupon.CouponFixtures.PRODUCT_ID;
import static com.backend.boundedContext.coupon.CouponFixtures.SELLER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponUpdateProductCouponUseCaseTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long COUPON_ID = 77L;
    private static final Long PARTNERSHIP_ID = 5L;

    @Mock CouponSupport couponSupport;
    @Mock CouponSellerPort couponSellerPort;
    @Mock CouponRepository couponRepository;
    @Mock ProductCouponRepository productCouponRepository;

    @InjectMocks CouponUpdateProductCouponUseCase useCase;

    private ProductCoupon givenCoupon(LocalDateTime validFrom, LocalDateTime validUntil) {
        Coupon coupon = CouponFixtures.withId(CouponFixtures.productCouponBase(validFrom, validUntil, 100), COUPON_ID);
        ProductCoupon productCoupon = CouponFixtures.productCoupon(coupon, PARTNERSHIP_ID, "ABCDEFGH23", CouponFixtures.FIXED_3000);
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(productCouponRepository.findPartnershipIdByCouponId(COUPON_ID)).willReturn(Optional.of(PARTNERSHIP_ID));
        given(couponRepository.findByIdForUpdate(COUPON_ID)).willReturn(Optional.of(coupon));
        given(productCouponRepository.findWithCouponByCouponId(COUPON_ID)).willReturn(Optional.of(productCoupon));
        return productCoupon;
    }

    private ProductCouponUpdateCommand onlyValidUntil(LocalDateTime validUntil) {
        return new ProductCouponUpdateCommand(null, null, null, null, null, validUntil, null, null);
    }

    @Test
    @DisplayName("시작 전: 보낸 항목만 바꾸고 나머지는 그대로 두며, 할인 방식을 바꾸면 새 할인 내용이 된다")
    void updateBeforeStart() {
        LocalDateTime now = LocalDateTime.now();
        ProductCoupon productCoupon = givenCoupon(now.plusDays(1), now.plusDays(10));

        useCase.update(MEMBER_ID, COUPON_ID, new ProductCouponUpdateCommand("새 이름", DiscountType.RATE, 10L, 4_000L,
                null, now.plusDays(20), null, null));

        Coupon coupon = productCoupon.getCoupon();
        assertThat(coupon.getName()).isEqualTo("새 이름");
        assertThat(coupon.getValidUntil()).isEqualTo(now.plusDays(20));
        assertThat(coupon.getTotalQuantity()).isEqualTo(100);   // 보내지 않음
        assertThat(productCoupon.discount()).isEqualTo(new ProductCouponDiscount(DiscountType.RATE, 10, 4_000L));
        verify(couponSupport).checkNotOverlapped(INFLUENCER_ID, PRODUCT_ID, coupon.getValidFrom(), now.plusDays(20), COUPON_ID);
    }

    @Test
    @DisplayName("시작 전: 수량 제한을 끄면 총 수량과 남은 수량이 없어진다")
    void updateBeforeStart_unlimited() {
        LocalDateTime now = LocalDateTime.now();
        ProductCoupon productCoupon = givenCoupon(now.plusDays(1), now.plusDays(10));

        useCase.update(MEMBER_ID, COUPON_ID, new ProductCouponUpdateCommand(null, null, null, null, null, null, false, null));

        assertThat(productCoupon.getCoupon().isQuantityLimited()).isFalse();
        assertThat(productCoupon.getCoupon().getTotalQuantity()).isNull();
        assertThat(productCoupon.getCoupon().getRemainingQuantity()).isNull();
    }

    @Test
    @DisplayName("잠금 순서는 협약 → 쿠폰이다 (협약 종료와 데드락이 나지 않게)")
    void lockOrder() {
        LocalDateTime now = LocalDateTime.now();
        givenCoupon(now.plusDays(1), now.plusDays(10));

        useCase.update(MEMBER_ID, COUPON_ID, onlyValidUntil(now.plusDays(20)));

        InOrder order = inOrder(couponSupport, couponRepository);
        order.verify(couponSupport).lockPartnership(PARTNERSHIP_ID);
        order.verify(couponRepository).findByIdForUpdate(COUPON_ID);
    }

    @Test
    @DisplayName("시작 후: 종료 일시 연장만 되고 겹침도 확인한다")
    void extendAfterStart() {
        LocalDateTime now = LocalDateTime.now();
        ProductCoupon productCoupon = givenCoupon(now.minusDays(1), now.plusDays(10));

        useCase.update(MEMBER_ID, COUPON_ID, onlyValidUntil(now.plusDays(30)));

        assertThat(productCoupon.getCoupon().getValidUntil()).isEqualTo(now.plusDays(30));
        verify(couponSupport).checkNotOverlapped(eq(INFLUENCER_ID), eq(PRODUCT_ID), any(), eq(now.plusDays(30)), eq(COUPON_ID));
    }

    @Test
    @DisplayName("시작 후 종료 일시 말고 다른 항목을 보내면 409-COUPON_NOT_EDITABLE이고 아무것도 바뀌지 않는다")
    void notEditableAfterStart() {
        LocalDateTime now = LocalDateTime.now();
        ProductCoupon productCoupon = givenCoupon(now.minusDays(1), now.plusDays(10));

        assertThatThrownBy(() -> useCase.update(MEMBER_ID, COUPON_ID,
                new ProductCouponUpdateCommand("새 이름", null, null, null, null, now.plusDays(30), null, null)))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_EDITABLE");
        assertThat(productCoupon.getCoupon().getName()).isEqualTo("인플루언서 쿠폰");
        verify(couponSupport, never()).checkNotOverlapped(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("내가 발행한 쿠폰이 아니면 403-FORBIDDEN이고 상품 쿠폰 정보를 읽지 않는다")
    void notMine() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = CouponFixtures.withId(CouponFixtures.productCouponBase(now.plusDays(1), now.plusDays(10), null), COUPON_ID);
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(999L);
        given(productCouponRepository.findPartnershipIdByCouponId(COUPON_ID)).willReturn(Optional.of(PARTNERSHIP_ID));
        given(couponRepository.findByIdForUpdate(COUPON_ID)).willReturn(Optional.of(coupon));

        assertThatThrownBy(() -> useCase.update(MEMBER_ID, COUPON_ID, onlyValidUntil(now.plusDays(20))))
                .extracting("resultCode").isEqualTo("403-FORBIDDEN");
        verify(productCouponRepository, never()).findWithCouponByCouponId(any());
    }

    @Test
    @DisplayName("상품 쿠폰이 아니거나 없는 쿠폰이면 잠그기 전에 404-COUPON_NOT_FOUND")
    void notProductCoupon() {
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(productCouponRepository.findPartnershipIdByCouponId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(MEMBER_ID, 1L, onlyValidUntil(LocalDateTime.now().plusDays(20))))
                .extracting("resultCode").isEqualTo("404-COUPON_NOT_FOUND");
        verify(couponSupport, never()).lockPartnership(any());
    }
}
