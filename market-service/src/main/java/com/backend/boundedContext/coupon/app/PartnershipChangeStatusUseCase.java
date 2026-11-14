package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipEndedBy;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 협약 상태 변경 (인플루언서 수락, 거절과 판매자, 인플루언서 종료, 판매자 제재로 자동 종료)
 *
 * <p>협약을 쓰기 잠금한 뒤 판단하므로 같은 협약의 수락, 종료, 쿠폰 발행이 겹치지 않는다.
 * 없는 협약은 404-PARTNERSHIP_NOT_FOUND, 내 협약이 아니면 403-FORBIDDEN, 상태가 맞지 않으면 409-PARTNERSHIP_STATE_INVALID.
 * 협약이 끝나면 그 협약으로 발행한 상품 쿠폰 중 아직 끝나지 않은 것을 모두 중지한다(SYSTEM).
 */
@Component
@RequiredArgsConstructor
public class PartnershipChangeStatusUseCase {

    static final String ENDED_STOP_REASON = "협약이 종료되어 자동 중지";
    static final String SELLER_SANCTIONED_STOP_REASON = "판매자 제재로 협약이 종료되어 자동 중지";

    private final CouponSupport couponSupport;
    private final CouponSellerPort couponSellerPort;
    private final CouponInfluencerPort couponInfluencerPort;
    private final PartnershipRepository partnershipRepository;
    private final CouponRepository couponRepository;

    // 인플루언서 수락 → ACTIVE
    public void accept(Long memberId, Long partnershipId) {
        lockForInfluencer(memberId, partnershipId).accept(LocalDateTime.now());
    }

    // 인플루언서 거절 → REJECTED
    public void reject(Long memberId, Long partnershipId) {
        lockForInfluencer(memberId, partnershipId).reject(LocalDateTime.now());
    }

    // 판매자 종료 → ENDED, 쿠폰 중지
    public void endBySeller(Long memberId, Long partnershipId) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        Partnership partnership = couponSupport.lockPartnership(partnershipId);
        if (!partnership.isOwnedBySeller(sellerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }

        end(partnership, PartnershipEndedBy.SELLER);
    }

    // 인플루언서 종료 → ENDED, 쿠폰 중지
    public void endByInfluencer(Long memberId, Long partnershipId) {
        end(lockForInfluencer(memberId, partnershipId), PartnershipEndedBy.INFLUENCER);
    }

    /**
     * 판매자 제재: 그 판매자의 요청 중이거나 진행 중인 협약을 모두 종료하고 쿠폰을 중지한다. 종료한 협약 수를 돌려준다.
     * 다시 불러도 이미 종료된 협약은 건드리지 않는다.
     */
    public int endAllBySeller(Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        List<Partnership> open = partnershipRepository.findAllForUpdateBySellerIdAndStatusIn(
                sellerId, List.of(PartnershipStatus.REQUESTED, PartnershipStatus.ACTIVE));
        if (open.isEmpty()) {
            return 0;
        }

        open.forEach(partnership -> partnership.endBySystem(now));
        couponRepository.stopActiveProductCouponsByPartnershipIds(
                open.stream().map(Partnership::getId).toList(), SELLER_SANCTIONED_STOP_REASON, now);
        return open.size();
    }

    private void end(Partnership partnership, PartnershipEndedBy endedBy) {
        LocalDateTime now = LocalDateTime.now();
        partnership.end(endedBy, now);
        couponRepository.stopActiveProductCouponsByPartnershipIds(List.of(partnership.getId()), ENDED_STOP_REASON, now);
    }

    // 로그인한 회원이 승인된 인플루언서이고 이 협약의 인플루언서인지 확인한 뒤 잠근 협약을 돌려준다
    private Partnership lockForInfluencer(Long memberId, Long partnershipId) {
        Long influencerId = couponInfluencerPort.getApprovedInfluencerIdByMemberId(memberId);
        Partnership partnership = couponSupport.lockPartnership(partnershipId);
        if (!partnership.isForInfluencer(influencerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }
        return partnership;
    }
}
