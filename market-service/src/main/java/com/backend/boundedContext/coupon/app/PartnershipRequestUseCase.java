package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.out.CouponInfluencer;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매자가 인플루언서에게 협약 요청 → REQUESTED
 *
 * <p>판매 가능한(APPROVED) 판매자만 요청할 수 있다(아니면 403-SELLER_NOT_APPROVED).
 * 없는 인플루언서면 404-INFLUENCER_NOT_FOUND, 승인되지 않은 인플루언서면 409-INFLUENCER_STATE_INVALID.
 * 같은 인플루언서와 요청 중이거나 진행 중인 협약이 있으면 409-PARTNERSHIP_ALREADY_EXISTS.
 * 같은 요청이 동시에 와서 확인을 함께 통과해도 부분 유니크 인덱스(uk_partnership_open)에 걸려 하나만 저장된다.
 */
@Component
@RequiredArgsConstructor
public class PartnershipRequestUseCase {

    private static final List<PartnershipStatus> OPEN_STATUSES = List.of(PartnershipStatus.REQUESTED, PartnershipStatus.ACTIVE);

    private final CouponSellerPort couponSellerPort;
    private final CouponInfluencerPort couponInfluencerPort;
    private final PartnershipRepository partnershipRepository;

    public Long request(Long memberId, Long influencerId, String message) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);

        CouponInfluencer influencer = couponInfluencerPort.findInfluencer(influencerId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.INFLUENCER_NOT_FOUND));
        if (!influencer.approved()) {
            throw new DomainException(CouponErrorCode.INFLUENCER_STATE_INVALID);
        }

        if (partnershipRepository.existsBySellerIdAndInfluencerIdAndStatusIn(sellerId, influencerId, OPEN_STATUSES)) {
            throw new DomainException(CouponErrorCode.PARTNERSHIP_ALREADY_EXISTS);
        }

        try {
            return partnershipRepository.saveAndFlush(
                    Partnership.request(sellerId, influencerId, message, LocalDateTime.now())).getId();
        } catch (DataIntegrityViolationException e) {
            // 확인 뒤 같은 요청이 먼저 커밋한 경우 (uk_partnership_open)
            throw new DomainException(CouponErrorCode.PARTNERSHIP_ALREADY_EXISTS, e);
        }
    }
}
