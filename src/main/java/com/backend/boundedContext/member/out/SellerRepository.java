package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.shared.member.dto.SellerStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    // 회원에게 해당 상태의 신청이 있으면 true (중복 신청 검사)
    boolean existsByMember_IdAndStatusIn(Long memberId, Collection<SellerStatus> statuses);

    // 사업자번호로 해당 상태의 신청이 있으면 true (중복 신청 검사)
    boolean existsByBusinessNumberAndStatusIn(String businessNumber, Collection<SellerStatus> statuses);
}