package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    Optional<Influencer> findByMemberId(Long memberId);

    Optional<Influencer> findByIdAndStatus(Long id, InfluencerStatus status);

    // 활동명 부분 일치 검색. keyword가 빈 문자열이면 그 상태 전체
    Page<Influencer> findByStatusAndActivityNameContainingIgnoreCaseOrderByIdDesc(InfluencerStatus status, String keyword, Pageable pageable);
}
