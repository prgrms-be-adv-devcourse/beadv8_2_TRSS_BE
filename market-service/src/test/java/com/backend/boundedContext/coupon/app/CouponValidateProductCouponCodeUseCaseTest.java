package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.ProductCouponValidationDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.boundedContext.coupon.out.CouponOrderPort;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.shared.order.dto.CartLineDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.backend.boundedContext.coupon.CouponFixtures.PRODUCT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CouponValidateProductCouponCodeUseCaseTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long COUPON_ID = 77L;
    private static final String CODE = "ABCDEFGH23";

    @Mock ProductCouponRepository productCouponRepository;
    @Mock CouponUsageRepository couponUsageRepository;
    @Mock CouponOrderPort couponOrderPort;

    @InjectMocks CouponValidateProductCouponCodeUseCase useCase;

    private ProductCoupon givenCoupon(ProductCouponDiscount discount, Integer totalQuantity) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = CouponFixtures.withId(CouponFixtures.productCouponBase(now.minusDays(1), now.plusDays(1), totalQuantity), COUPON_ID);
        ProductCoupon productCoupon = CouponFixtures.productCoupon(coupon, 5L, CODE, discount);
        given(productCouponRepository.findWithCouponByCode(CODE)).willReturn(Optional.of(productCoupon));
        return productCoupon;
    }

    private void givenNotUsed() {
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(false);
    }

    @Test
    @DisplayName("코드를 대소문자와 공백 상관없이 찾고, 구매자가 지정한 대상 상품 항목에 대한 예상 할인액을 준다")
    void validate() {
        givenCoupon(new ProductCouponDiscount(DiscountType.RATE, 10, 5_000L), null);
        givenNotUsed();
        given(couponOrderPort.getCartLines(MEMBER_ID, List.of(2L))).willReturn(List.of(
                new CartLineDto(2L, PRODUCT_ID, 15_000, 2, true)));

        ProductCouponValidationDto result = useCase.validate(MEMBER_ID, "  abcdefgh23 ", 2L);

        assertThat(result.couponId()).isEqualTo(COUPON_ID);
        assertThat(result.code()).isEqualTo(CODE);
        assertThat(result.cartItemId()).isEqualTo(2L);
        assertThat(result.lineAmount()).isEqualTo(30_000);
        assertThat(result.expectedDiscountAmount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("없는 코드면 404-COUPON_CODE_NOT_FOUND")
    void notFound() {
        given(productCouponRepository.findWithCouponByCode("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.validate(MEMBER_ID, "nope", 1L))
                .extracting("resultCode").isEqualTo("404-COUPON_CODE_NOT_FOUND");
        verifyNoInteractions(couponOrderPort);
    }

    @Test
    @DisplayName("중지됐거나 기간 밖이면 409-COUPON_NOT_ACTIVE")
    void notActive() {
        ProductCoupon productCoupon = givenCoupon(CouponFixtures.FIXED_3000, null);
        productCoupon.getCoupon().stop(CouponStoppedBy.SELLER, null, LocalDateTime.now());

        assertThatThrownBy(() -> useCase.validate(MEMBER_ID, CODE, 1L))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
    }

    @Test
    @DisplayName("수량이 모두 소진되면 409-COUPON_SOLD_OUT")
    void soldOut() {
        ProductCoupon productCoupon = givenCoupon(CouponFixtures.FIXED_3000, 10);
        ReflectionTestUtils.setField(productCoupon.getCoupon(), "remainingQuantity", 0);

        assertThatThrownBy(() -> useCase.validate(MEMBER_ID, CODE, 1L))
                .extracting("resultCode").isEqualTo("409-COUPON_SOLD_OUT");
    }

    @Test
    @DisplayName("이미 사용했거나 예약했으면 409-COUPON_ALREADY_USED")
    void alreadyUsed() {
        givenCoupon(CouponFixtures.FIXED_3000, null);
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(true);

        assertThatThrownBy(() -> useCase.validate(MEMBER_ID, CODE, 1L))
                .extracting("resultCode").isEqualTo("409-COUPON_ALREADY_USED");
        verifyNoInteractions(couponOrderPort);
    }

    @Test
    @DisplayName("지정한 항목이 대상 상품이 아니거나 판매할 수 없으면 400-COUPON_NOT_APPLICABLE")
    void notApplicable() {
        givenCoupon(CouponFixtures.FIXED_3000, null);
        givenNotUsed();
        given(couponOrderPort.getCartLines(MEMBER_ID, List.of(1L))).willReturn(List.of(new CartLineDto(1L, 999L, 10_000, 1, true)));
        given(couponOrderPort.getCartLines(MEMBER_ID, List.of(2L))).willReturn(List.of(new CartLineDto(2L, PRODUCT_ID, 0, 1, false)));

        assertThatThrownBy(() -> useCase.validate(MEMBER_ID, CODE, 1L))
                .extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
        assertThatThrownBy(() -> useCase.validate(MEMBER_ID, CODE, 2L))
                .extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("대상 상품의 옵션이 여러 개면 구매자가 지정한 항목 금액으로만 계산한다(보내는 순서와 상관없음)")
    void validate_buyerChoosesLine() {
        givenCoupon(new ProductCouponDiscount(DiscountType.RATE, 20, 20_000L), null);
        givenNotUsed();
        given(couponOrderPort.getCartLines(MEMBER_ID, List.of(1L))).willReturn(List.of(new CartLineDto(1L, PRODUCT_ID, 10_000, 1, true)));
        given(couponOrderPort.getCartLines(MEMBER_ID, List.of(2L))).willReturn(List.of(new CartLineDto(2L, PRODUCT_ID, 50_000, 1, true)));

        assertThat(useCase.validate(MEMBER_ID, CODE, 1L).expectedDiscountAmount()).isEqualTo(2_000);
        assertThat(useCase.validate(MEMBER_ID, CODE, 2L).expectedDiscountAmount()).isEqualTo(10_000);
    }
}
