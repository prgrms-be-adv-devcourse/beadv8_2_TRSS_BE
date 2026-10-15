package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.shared.member.dto.SellerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    // 회원의 판매자 (회원 1 : 판매자 1)
    Optional<Seller> findByMemberId(Long memberId);

    // 관리자 회원 목록: 한 페이지 회원들의 판매자를 한 번에 읽는다 (회원마다 따로 조회하지 않게)
    List<Seller> findAllByMemberIdIn(Collection<Long> memberIds);

    // 이 사업자번호를 쓰는 판매자가 있으면 true (반려·본인 철회된 판매자는 번호가 비어 있어 걸리지 않음)
    boolean existsByBusinessNumber(String businessNumber);

    // 관리자 판매자 목록: 심사 대기(PENDING) 먼저, 그다음 최근 신청순. status가 null이면 전체
    // 정렬이 쿼리에 고정돼 있으니 pageable에는 정렬 없이 page·size만 넘긴다
    // 목록에 신청자 이메일을 보여 줘서 회원을 함께 읽는다 (판매자마다 회원을 따로 조회하지 않게)
    @Query(value = """
            select s
            from Seller s
            join fetch s.member
            where (:status is null or s.status = :status)
            order by case when s.status = com.backend.shared.member.dto.SellerStatus.PENDING then 0 else 1 end,
                     s.appliedAt desc, 
                     s.id desc
            """,
            countQuery = """
            select count(s) from Seller s
            where (:status is null or s.status = :status)
            """)
    Page<Seller> findAllForAdmin(@Param("status") SellerStatus status, Pageable pageable);
}
