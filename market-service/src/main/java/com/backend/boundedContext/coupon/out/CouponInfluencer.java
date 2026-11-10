package com.backend.boundedContext.coupon.out;

/**
 * 쿠폰이 보는 인플루언서 정보 ({@link CouponInfluencerPort})
 *
 * @param influencerId 인플루언서 ID(influencer.id, 회원 ID가 아님)
 * @param memberId     인플루언서의 회원 ID
 * @param activityName 활동명
 * @param approved     승인된(APPROVED) 인플루언서인지
 */
public record CouponInfluencer(
        Long influencerId,
        Long memberId,
        String activityName,
        boolean approved
) {
}
