package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.shared.member.dto.SellerStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    // 회원에게 해당 상태의 신청이 있으면 true (중복 신청 검사)
    boolean existsByMember_IdAndStatusIn(Long memberId, Collection<SellerStatus> statuses);

    // 사업자번호로 해당 상태의 신청이 있으면 true (중복 신청 검사)
    boolean existsByBusinessNumberAndStatusIn(String businessNumber, Collection<SellerStatus> statuses);

    // 회원의 가장 최근 신청 1건 (진행 중인 신청이 있으면 항상 그것이 가장 최근)
    Optional<Seller> findFirstByMember_IdOrderByIdDesc(Long memberId);
}