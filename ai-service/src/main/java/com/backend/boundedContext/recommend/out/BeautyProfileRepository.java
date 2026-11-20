package com.backend.boundedContext.recommend.out;

import com.backend.boundedContext.recommend.domain.BeautyProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeautyProfileRepository extends JpaRepository<BeautyProfile, Long> {

    // 회원당 1개 (uk_beauty_profile_member_id)
    Optional<BeautyProfile> findByMemberId(Long memberId);

    // 크리에이터 추천 후보: 설문을 한 인플루언서 중 나를 뺀 사람 (idx_beauty_profile_influencer)
    List<BeautyProfile> findByInfluencerIdIsNotNullAndMemberIdNot(Long memberId);
}
