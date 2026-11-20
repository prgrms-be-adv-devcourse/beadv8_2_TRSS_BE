package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.SellerStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SellerStatusHistoryRepository extends JpaRepository<SellerStatusHistory, Long> {

    // 판매자의 가장 최근 상태 변경 (반려 사유 등을 보여 줄 때)
    Optional<SellerStatusHistory> findFirstBySellerIdOrderByIdDesc(Long sellerId);

    // 판매자의 이력 전체, 오래된 순
    List<SellerStatusHistory> findAllBySellerIdOrderByIdAsc(Long sellerId);
}
