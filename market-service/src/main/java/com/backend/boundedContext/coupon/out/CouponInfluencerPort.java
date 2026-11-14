package com.backend.boundedContext.coupon.out;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 쿠폰(협약, 상품 쿠폰)이 쓰는 인플루언서 기능. 인플루언서는 member-service가 관리한다.
 *
 * 구현은 {@link CouponInfluencerMemberApiAdapter}(회원 서비스 내부 API 호출)
 */
public interface CouponInfluencerPort {

    /** 인플루언서 조회 (협약 요청 대상 확인). 없으면 empty */
    Optional<CouponInfluencer> findInfluencer(Long influencerId);

    /** 로그인한 회원의 승인된 인플루언서 ID. 인플루언서가 아니거나 승인되지 않았으면 403-INFLUENCER_NOT_APPROVED */
    Long getApprovedInfluencerIdByMemberId(Long memberId);

    /** 인플루언서 ID → 활동명 (목록 표시용). 없는 ID는 결과에서 빠진다 */
    Map<Long, String> getActivityNames(Collection<Long> influencerIds);
}
