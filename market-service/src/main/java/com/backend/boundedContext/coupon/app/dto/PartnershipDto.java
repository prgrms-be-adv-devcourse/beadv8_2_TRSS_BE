package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipEndedBy;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;

import java.time.LocalDateTime;

/**
 * 협약 한 줄 (판매자, 인플루언서, 관리자 목록 공통)
 *
 * @param businessName 판매자 상호명. 조회하지 못하면 null
 * @param activityName 인플루언서 활동명. 조회하지 못하면 null
 * @param acceptedAt   수락 시각. 수락 전이면 null
 * @param rejectedAt   거절 시각. 거절되지 않았으면 null
 * @param endedAt      종료 시각. 종료되지 않았으면 null
 * @param endedBy      종료 주체(SELLER, INFLUENCER, SYSTEM). 종료되지 않았으면 null
 */
public record PartnershipDto(
        Long partnershipId,
        Long sellerId,
        String businessName,
        Long influencerId,
        String activityName,
        PartnershipStatus status,
        Integer commissionRate,     // 인플루언서 수수료율(%)
        String requestMessage,
        LocalDateTime requestedAt,
        LocalDateTime acceptedAt,
        LocalDateTime rejectedAt,
        LocalDateTime endedAt,
        PartnershipEndedBy endedBy
) {
    public static PartnershipDto of(Partnership partnership, String businessName, String activityName) {
        return new PartnershipDto(
                partnership.getId(),
                partnership.getSellerId(),
                businessName,
                partnership.getInfluencerId(),
                activityName,
                partnership.getStatus(),
                partnership.getCommissionRate(),
                partnership.getRequestMessage(),
                partnership.getRequestedAt(),
                partnership.getAcceptedAt(),
                partnership.getRejectedAt(),
                partnership.getEndedAt(),
                partnership.getEndedBy()
        );
    }
}
