package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponCreateCartCouponUseCaseTest {

    @Mock CouponRepository couponRepository;

    @InjectMocks CouponCreateCartCouponUseCase useCase;

    @Test
    @DisplayName("입력값으로 장바구니 쿠폰을 만들어 저장하고 ID를 돌려준다")
    void create() {
        LocalDateTime now = LocalDateTime.now();
        CartCouponCreateCommand command = new CartCouponCreateCommand("11월 장바구니 쿠폰", CouponFixtures.TIERS,
                true, 500, now.plusDays(1), now.plusDays(30));
        given(couponRepository.save(any(Coupon.class))).willAnswer(invocation -> {
            Coupon coupon = invocation.getArgument(0);
            ReflectionTestUtils.setField(coupon, "id", 10L);
            return coupon;
        });

        Long couponId = useCase.create(1L, command);

        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).save(captor.capture());
        Coupon saved = captor.getValue();
        assertThat(couponId).isEqualTo(10L);
        assertThat(saved.getCouponType()).isEqualTo(CouponType.CART);
        assertThat(saved.getIssuerId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("11월 장바구니 쿠폰");
        assertThat(saved.getRemainingQuantity()).isEqualTo(500);
        assertThat(saved.getTiers()).hasSize(3);
    }

    @Test
    @DisplayName("유효 기간이 틀리면 400-COUPON_PERIOD_INVALID이고 저장하지 않는다")
    void periodInvalid() {
        LocalDateTime now = LocalDateTime.now();
        CartCouponCreateCommand command = new CartCouponCreateCommand("쿠폰", CouponFixtures.TIERS,
                false, null, now.plusDays(2), now.plusDays(1));

        assertThatThrownBy(() -> useCase.create(1L, command))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-COUPON_PERIOD_INVALID");
        verify(couponRepository, never()).save(any());
    }
}
