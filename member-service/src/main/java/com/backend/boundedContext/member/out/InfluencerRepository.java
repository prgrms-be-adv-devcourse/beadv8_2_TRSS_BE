package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Influencer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    Optional<Influencer> findByMemberId(Long memberId);
}
