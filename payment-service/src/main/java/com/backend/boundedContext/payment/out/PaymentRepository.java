package com.backend.boundedContext.payment.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;

// 관리자 결제 조회는 AdminPaymentSpecifications 조건으로 findAll(spec, pageable)을 씀
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    // 같은 멱등 키로 이미 처리한 결제 (멱등 키는 UNIQUE라 최대 1건)
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    // 결제 취소 전에 지갑을 먼저 잠그려고 구매자만 꺼낸다 (결제 엔티티는 잠금 뒤에 새로 읽는다)
    @Query("select p.memberId from Payment p where p.idempotencyKey = :idempotencyKey")
    Optional<Long> findMemberIdByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    // 주문에 이 상태의 결제가 있는지 (PAID는 주문당 1건)
    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);

    // 주문의 결제 시도 전체, 오래된 순 (같은 시각이면 ID 순). idx_payment_order(order_id, created_at) 사용
    List<Payment> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);

    /**
     * 관리자 실패 거래: 이 상태들 중 하나이고 [from, to) 사이에 마지막으로 바뀐 결제 건
     * from, to가 없으면 호출하는 쪽에서 아주 이른, 아주 늦은 시각을 넘긴다(null 파라미터 타입 문제를 피함)
     */
    @Query("""
            select p from Payment p
             where p.status in :statuses
               and p.updatedAt >= :from
               and p.updatedAt < :to
            """)
    Page<Payment> findFailures(@Param("statuses") Collection<PaymentStatus> statuses,
                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    /** 관리자 실패 거래: findFailures와 같은 조건의 건수(유형을 합쳐 보여 줄 때 전체 건수와 읽을 범위를 먼저 정한다) */
    @Query("""
            select count(p) from Payment p
             where p.status in :statuses
               and p.updatedAt >= :from
               and p.updatedAt < :to
            """)
    long countFailures(@Param("statuses") Collection<PaymentStatus> statuses,
                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
