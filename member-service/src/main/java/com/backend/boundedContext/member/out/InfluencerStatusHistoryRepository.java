package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.InfluencerStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InfluencerStatusHistoryRepository extends JpaRepository<InfluencerStatusHistory, Long> {

    Optional<InfluencerStatusHistory> findFirstByInfluencerIdOrderByIdDesc(Long influencerId);

    List<InfluencerStatusHistory> findAllByInfluencerIdOrderByIdAsc(Long influencerId);
}
