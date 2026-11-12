package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    Optional<Influencer> findByMemberId(Long memberId);

    Optional<Influencer> findByIdAndStatus(Long id, InfluencerStatus status);

    // 활동명 부분 일치 검색. keyword가 빈 문자열이면 그 상태 전체
    Page<Influencer> findByStatusAndActivityNameContainingIgnoreCaseOrderByIdDesc(InfluencerStatus status, String keyword, Pageable pageable);

    // 관리자 목록: 심사 대기(PENDING) 먼저, 그다음 최근 신청순. status가 null이면 전체
    // 정렬이 쿼리에 고정돼 있으니 pageable에는 page·size만 넘긴다. 이메일을 보여 줘서 회원을 함께 읽는다
    @Query(value = """
            select i
            from Influencer i
            join fetch i.member
            where (:status is null or i.status = :status)
            order by case when i.status = com.backend.boundedContext.member.domain.InfluencerStatus.PENDING then 0 else 1 end,
                     i.appliedAt desc,
                     i.id desc
            """,
            countQuery = """
            select count(i) from Influencer i
            where (:status is null or i.status = :status)
            """)
    Page<Influencer> findAllForAdmin(@Param("status") InfluencerStatus status, Pageable pageable);

    // 관리자 목록의 탭별 건수. 인플루언서가 없는 상태는 결과에 없다
    @Query("""
            select new com.backend.boundedContext.member.out.InfluencerStatusCountRow(i.status, count(i))
            from Influencer i
            group by i.status
            """)
    List<InfluencerStatusCountRow> countGroupByStatus();
}
