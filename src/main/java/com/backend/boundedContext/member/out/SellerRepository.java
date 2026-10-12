package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    // 회원의 판매자 (회원 1 : 판매자 1)
    Optional<Seller> findByMemberId(Long memberId);

    // 이 사업자번호를 쓰는 판매자가 있으면 true (반려·본인 철회된 판매자는 번호가 비어 있어 걸리지 않음)
    boolean existsByBusinessNumber(String businessNumber);
}
