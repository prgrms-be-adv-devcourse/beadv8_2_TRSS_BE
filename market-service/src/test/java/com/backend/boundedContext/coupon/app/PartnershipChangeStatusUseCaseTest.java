package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipEndedBy;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PartnershipChangeStatusUseCaseTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long PARTNERSHIP_ID = 5L;

    @Mock CouponSupport couponSupport;
    @Mock CouponSellerPort couponSellerPort;
    @Mock CouponInfluencerPort couponInfluencerPort;
    @Mock PartnershipRepository partnershipRepository;
    @Mock CouponRepository couponRepository;

    @InjectMocks PartnershipChangeStatusUseCase useCase;

    private Partnership requested() {
        return CouponFixtures.withId(
                Partnership.request(CouponFixtures.SELLER_ID, CouponFixtures.INFLUENCER_ID, 20, null, LocalDateTime.now()), PARTNERSHIP_ID);
    }

    @Test
    @DisplayName("인플루언서가 잠근 협약을 수락하면 ACTIVE가 된다")
    void accept() {
        Partnership partnership = requested();
        given(couponInfluencerPort.getApprovedInfluencerIdByMemberId(MEMBER_ID)).willReturn(CouponFixtures.INFLUENCER_ID);
        given(couponSupport.lockPartnership(PARTNERSHIP_ID)).willReturn(partnership);

        useCase.accept(MEMBER_ID, PARTNERSHIP_ID);

        assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("다른 인플루언서의 협약이면 403-FORBIDDEN이고 바뀌지 않는다")
    void notMine() {
        Partnership partnership = requested();
        given(couponInfluencerPort.getApprovedInfluencerIdByMemberId(MEMBER_ID)).willReturn(999L);
        given(couponSupport.lockPartnership(PARTNERSHIP_ID)).willReturn(partnership);

        assertThatThrownBy(() -> useCase.reject(MEMBER_ID, PARTNERSHIP_ID)).extracting("resultCode").isEqualTo("403-FORBIDDEN");
        assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.REQUESTED);
    }

    @Test
    @DisplayName("승인된 인플루언서가 아니면 협약을 조회하기 전에 403-INFLUENCER_NOT_APPROVED")
    void influencerNotApproved() {
        given(couponInfluencerPort.getApprovedInfluencerIdByMemberId(MEMBER_ID))
                .willThrow(new DomainException(CouponErrorCode.INFLUENCER_NOT_APPROVED));

        assertThatThrownBy(() -> useCase.accept(MEMBER_ID, PARTNERSHIP_ID))
                .extracting("resultCode").isEqualTo("403-INFLUENCER_NOT_APPROVED");
        verify(couponSupport, never()).lockPartnership(any());
    }

    @Test
    @DisplayName("판매자가 진행 중 협약을 종료하면 ENDED가 되고 그 협약의 쿠폰을 중지한다")
    void endBySeller() {
        Partnership partnership = CouponFixtures.withId(CouponFixtures.activePartnership(), PARTNERSHIP_ID);
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(CouponFixtures.SELLER_ID);
        given(couponSupport.lockPartnership(PARTNERSHIP_ID)).willReturn(partnership);

        useCase.endBySeller(MEMBER_ID, PARTNERSHIP_ID);

        assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.ENDED);
        assertThat(partnership.getEndedBy()).isEqualTo(PartnershipEndedBy.SELLER);
        verify(couponRepository).stopActiveProductCouponsByPartnershipIds(eq(List.of(PARTNERSHIP_ID)),
                eq(PartnershipChangeStatusUseCase.ENDED_STOP_REASON), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("다른 판매자의 협약을 종료하면 403-FORBIDDEN이고 쿠폰을 건드리지 않는다")
    void endBySeller_notMine() {
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(999L);
        given(couponSupport.lockPartnership(PARTNERSHIP_ID))
                .willReturn(CouponFixtures.withId(CouponFixtures.activePartnership(), PARTNERSHIP_ID));

        assertThatThrownBy(() -> useCase.endBySeller(MEMBER_ID, PARTNERSHIP_ID)).extracting("resultCode").isEqualTo("403-FORBIDDEN");
        verify(couponRepository, never()).stopActiveProductCouponsByPartnershipIds(anyCollection(), anyString(), any());
    }

    @Test
    @DisplayName("판매자 제재: 요청 중, 진행 중 협약을 모두 SYSTEM 종료하고 쿠폰을 한 번에 중지한다")
    void endAllBySeller() {
        Partnership pending = CouponFixtures.withId(requested(), 5L);
        Partnership active = CouponFixtures.withId(CouponFixtures.activePartnership(), 6L);
        given(partnershipRepository.findAllForUpdateBySellerIdAndStatusIn(eq(CouponFixtures.SELLER_ID), anyCollection()))
                .willReturn(List.of(pending, active));

        int ended = useCase.endAllBySeller(CouponFixtures.SELLER_ID);

        assertThat(ended).isEqualTo(2);
        assertThat(pending.getEndedBy()).isEqualTo(PartnershipEndedBy.SYSTEM);
        assertThat(active.getEndedBy()).isEqualTo(PartnershipEndedBy.SYSTEM);
        verify(couponRepository).stopActiveProductCouponsByPartnershipIds(eq(List.of(5L, 6L)),
                eq(PartnershipChangeStatusUseCase.SELLER_SANCTIONED_STOP_REASON), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("판매자 제재: 열린 협약이 없으면 쿠폰을 건드리지 않는다")
    void endAllBySeller_none() {
        given(partnershipRepository.findAllForUpdateBySellerIdAndStatusIn(eq(CouponFixtures.SELLER_ID), anyCollection()))
                .willReturn(List.of());

        assertThat(useCase.endAllBySeller(CouponFixtures.SELLER_ID)).isZero();
        verify(couponRepository, never()).stopActiveProductCouponsByPartnershipIds(anyCollection(), anyString(), any());
    }
}
