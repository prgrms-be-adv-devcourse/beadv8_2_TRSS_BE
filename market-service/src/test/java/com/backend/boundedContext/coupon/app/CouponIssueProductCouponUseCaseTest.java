package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueResultDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipEndedBy;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponProductPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.InfluencerPickPort;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.dto.ProductSnapshotDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.backend.boundedContext.coupon.CouponFixtures.INFLUENCER_ID;
import static com.backend.boundedContext.coupon.CouponFixtures.PRODUCT_ID;
import static com.backend.boundedContext.coupon.CouponFixtures.SELLER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponIssueProductCouponUseCaseTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long PARTNERSHIP_ID = 5L;

    @Mock CouponSupport couponSupport;
    @Mock CouponSellerPort couponSellerPort;
    @Mock CouponProductPort couponProductPort;
    @Mock InfluencerPickPort influencerPickPort;
    @Mock CouponRepository couponRepository;
    @Mock ProductCouponRepository productCouponRepository;
    @Mock CouponCodeGenerator couponCodeGenerator;

    @InjectMocks CouponIssueProductCouponUseCase useCase;

    private ProductCouponIssueCommand command() {
        LocalDateTime now = LocalDateTime.now();
        return new ProductCouponIssueCommand(PARTNERSHIP_ID, PRODUCT_ID, "인플루언서 쿠폰", CouponFixtures.FIXED_3000,
                now.plusDays(1), now.plusDays(10), true, 100);
    }

    private Partnership givenPartnership() {
        Partnership partnership = CouponFixtures.withId(CouponFixtures.activePartnership(), PARTNERSHIP_ID);
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(couponSupport.lockPartnership(PARTNERSHIP_ID)).willReturn(partnership);
        return partnership;
    }

    private void givenProduct(Long sellerId, boolean onSale) {
        given(couponProductPort.getProducts(List.of(PRODUCT_ID)))
                .willReturn(List.of(new ProductSnapshotDto(PRODUCT_ID, sellerId, "립스틱", 20_000, onSale, null)));
    }

    @Test
    @DisplayName("협약 → 상품 잠금 → pick → 기간 겹침을 확인한 뒤 쿠폰과 상품 쿠폰을 저장하고 코드를 돌려준다")
    void issue() {
        givenPartnership();
        givenProduct(SELLER_ID, true);
        given(influencerPickPort.isPicked(INFLUENCER_ID, PRODUCT_ID)).willReturn(true);
        given(couponCodeGenerator.generate()).willReturn("DUPLICATE2", "ABCDEFGH23");
        given(productCouponRepository.existsByCode("DUPLICATE2")).willReturn(true);
        given(productCouponRepository.existsByCode("ABCDEFGH23")).willReturn(false);
        given(couponRepository.save(any(Coupon.class))).willAnswer(invocation -> {
            Coupon coupon = invocation.getArgument(0);
            ReflectionTestUtils.setField(coupon, "id", 77L);
            return coupon;
        });

        ProductCouponIssueResultDto result = useCase.issue(MEMBER_ID, command());

        assertThat(result.couponId()).isEqualTo(77L);
        assertThat(result.code()).isEqualTo("ABCDEFGH23");
        verify(couponProductPort).lockProductForShare(PRODUCT_ID);
        verify(couponSupport).checkNotOverlapped(eq(INFLUENCER_ID), eq(PRODUCT_ID), any(), any(), isNull());

        ArgumentCaptor<ProductCoupon> captor = ArgumentCaptor.forClass(ProductCoupon.class);
        verify(productCouponRepository).save(captor.capture());
        ProductCoupon saved = captor.getValue();
        assertThat(saved.getCoupon().getCouponType()).isEqualTo(CouponType.PRODUCT);
        assertThat(saved.getCoupon().getIssuerId()).isEqualTo(SELLER_ID);
        assertThat(saved.getPartnershipId()).isEqualTo(PARTNERSHIP_ID);
        assertThat(saved.getInfluencerId()).isEqualTo(INFLUENCER_ID);
        assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getCode()).isEqualTo("ABCDEFGH23");
    }

    @Test
    @DisplayName("내 협약이 아니면 403-FORBIDDEN, 진행 중이 아니면 409-PARTNERSHIP_NOT_ACTIVE")
    void partnershipInvalid() {
        Partnership partnership = givenPartnership();
        given(couponSellerPort.getApprovedSellerId(2L)).willReturn(999L);

        assertThatThrownBy(() -> useCase.issue(2L, command())).extracting("resultCode").isEqualTo("403-FORBIDDEN");

        partnership.end(PartnershipEndedBy.INFLUENCER, LocalDateTime.now());
        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command()))
                .extracting("resultCode").isEqualTo("409-PARTNERSHIP_NOT_ACTIVE");
        verify(couponProductPort, never()).lockProductForShare(any());
    }

    @Test
    @DisplayName("다른 판매자의 상품이거나 없는 상품이면 400-PRODUCT_NOT_OWNED")
    void productNotOwned() {
        givenPartnership();
        givenProduct(999L, true);

        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command())).extracting("resultCode").isEqualTo("400-PRODUCT_NOT_OWNED");

        given(couponProductPort.getProducts(List.of(PRODUCT_ID))).willReturn(List.of());
        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command())).extracting("resultCode").isEqualTo("400-PRODUCT_NOT_OWNED");
    }

    @Test
    @DisplayName("판매 중이 아닌 상품이면 409-PRODUCT_NOT_ON_SALE")
    void productNotOnSale() {
        givenPartnership();
        givenProduct(SELLER_ID, false);

        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command())).extracting("resultCode").isEqualTo("409-PRODUCT_NOT_ON_SALE");
    }

    @Test
    @DisplayName("협약 인플루언서가 pick하지 않은 상품이면 400-PRODUCT_NOT_PICKED (지금 임시 구현체는 항상 이 응답)")
    void productNotPicked() {
        givenPartnership();
        givenProduct(SELLER_ID, true);
        given(influencerPickPort.isPicked(INFLUENCER_ID, PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command())).extracting("resultCode").isEqualTo("400-PRODUCT_NOT_PICKED");
        verify(couponRepository, never()).save(any());
    }

    @Test
    @DisplayName("기간이 겹치는 쿠폰이 있으면 409-COUPON_PERIOD_OVERLAPPED이고 저장하지 않는다")
    void overlapped() {
        givenPartnership();
        givenProduct(SELLER_ID, true);
        given(influencerPickPort.isPicked(INFLUENCER_ID, PRODUCT_ID)).willReturn(true);
        given(couponCodeGenerator.generate()).willReturn("ABCDEFGH23");
        willThrow(new DomainException(CouponErrorCode.COUPON_PERIOD_OVERLAPPED))
                .given(couponSupport).checkNotOverlapped(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command()))
                .extracting("resultCode").isEqualTo("409-COUPON_PERIOD_OVERLAPPED");
        verify(couponRepository, never()).save(any());
        verify(productCouponRepository, never()).save(any());
    }

    @Test
    @DisplayName("코드가 계속 겹치면 정해진 횟수만큼만 다시 만들고 실패한다")
    void codeRetryLimit() {
        givenPartnership();
        givenProduct(SELLER_ID, true);
        given(influencerPickPort.isPicked(INFLUENCER_ID, PRODUCT_ID)).willReturn(true);
        given(couponCodeGenerator.generate()).willReturn("ABCDEFGH23");
        given(productCouponRepository.existsByCode(anyString())).willReturn(true);

        assertThatThrownBy(() -> useCase.issue(MEMBER_ID, command())).isInstanceOf(IllegalStateException.class);
        verify(couponCodeGenerator, times(CouponIssueProductCouponUseCase.CODE_RETRY_LIMIT)).generate();
    }
}
