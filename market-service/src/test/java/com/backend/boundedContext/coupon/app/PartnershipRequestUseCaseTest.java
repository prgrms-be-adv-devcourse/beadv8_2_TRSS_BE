package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.out.CouponInfluencer;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PartnershipRequestUseCaseTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long SELLER_ID = 20L;
    private static final Long INFLUENCER_ID = 30L;

    @Mock CouponSellerPort couponSellerPort;
    @Mock CouponInfluencerPort couponInfluencerPort;
    @Mock PartnershipRepository partnershipRepository;

    @InjectMocks PartnershipRequestUseCase useCase;

    private void givenSellerAndInfluencer(boolean approved) {
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(couponInfluencerPort.findInfluencer(INFLUENCER_ID))
                .willReturn(Optional.of(new CouponInfluencer(INFLUENCER_ID, 99L, "뷰티언니", approved)));
    }

    @Test
    @DisplayName("승인된 인플루언서에게 요청하면 REQUESTED 협약을 저장하고 ID를 돌려준다")
    void request() {
        givenSellerAndInfluencer(true);
        given(partnershipRepository.existsBySellerIdAndInfluencerIdAndStatusIn(eq(SELLER_ID), eq(INFLUENCER_ID), anyCollection()))
                .willReturn(false);
        given(partnershipRepository.saveAndFlush(any(Partnership.class))).willAnswer(invocation -> {
            Partnership partnership = invocation.getArgument(0);
            ReflectionTestUtils.setField(partnership, "id", 5L);
            return partnership;
        });

        Long partnershipId = useCase.request(MEMBER_ID, INFLUENCER_ID, "함께해요");

        ArgumentCaptor<Partnership> captor = ArgumentCaptor.forClass(Partnership.class);
        verify(partnershipRepository).saveAndFlush(captor.capture());
        assertThat(partnershipId).isEqualTo(5L);
        assertThat(captor.getValue().getStatus()).isEqualTo(PartnershipStatus.REQUESTED);
        assertThat(captor.getValue().getSellerId()).isEqualTo(SELLER_ID);
        assertThat(captor.getValue().getRequestMessage()).isEqualTo("함께해요");
    }

    @Test
    @DisplayName("없는 인플루언서면 404-INFLUENCER_NOT_FOUND (지금 임시 구현체는 항상 이 응답)")
    void influencerNotFound() {
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(couponInfluencerPort.findInfluencer(INFLUENCER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.request(MEMBER_ID, INFLUENCER_ID, null))
                .extracting("resultCode").isEqualTo("404-INFLUENCER_NOT_FOUND");
    }

    @Test
    @DisplayName("승인되지 않은 인플루언서면 409-INFLUENCER_STATE_INVALID")
    void influencerNotApproved() {
        givenSellerAndInfluencer(false);

        assertThatThrownBy(() -> useCase.request(MEMBER_ID, INFLUENCER_ID, null))
                .extracting("resultCode").isEqualTo("409-INFLUENCER_STATE_INVALID");
        verify(partnershipRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("요청 중이거나 진행 중인 협약이 있으면 409-PARTNERSHIP_ALREADY_EXISTS")
    void alreadyExists() {
        givenSellerAndInfluencer(true);
        given(partnershipRepository.existsBySellerIdAndInfluencerIdAndStatusIn(eq(SELLER_ID), eq(INFLUENCER_ID), anyCollection()))
                .willReturn(true);

        assertThatThrownBy(() -> useCase.request(MEMBER_ID, INFLUENCER_ID, null))
                .extracting("resultCode").isEqualTo("409-PARTNERSHIP_ALREADY_EXISTS");
        verify(partnershipRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 요청이 먼저 저장돼 유니크 인덱스에 걸리면 409-PARTNERSHIP_ALREADY_EXISTS")
    void uniqueViolation() {
        givenSellerAndInfluencer(true);
        given(partnershipRepository.existsBySellerIdAndInfluencerIdAndStatusIn(eq(SELLER_ID), eq(INFLUENCER_ID), anyCollection()))
                .willReturn(false);
        given(partnershipRepository.saveAndFlush(any(Partnership.class)))
                .willThrow(new DataIntegrityViolationException("uk_partnership_open"));

        assertThatThrownBy(() -> useCase.request(MEMBER_ID, INFLUENCER_ID, null))
                .extracting("resultCode").isEqualTo("409-PARTNERSHIP_ALREADY_EXISTS");
    }
}
