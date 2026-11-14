package com.backend.boundedContext.recommend.out;

import com.backend.boundedContext.recommend.domain.BeautyProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BeautyProfileRepository extends JpaRepository<BeautyProfile, Long> {

    // 회원당 1개 (uk_beauty_profile_member_id)
    Optional<BeautyProfile> findByMemberId(Long memberId);
}
