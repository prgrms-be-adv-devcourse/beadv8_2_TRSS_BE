package com.backend.boundedContext.payment.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;

import jakarta.persistence.LockModeType;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    Optional<PaymentRefund> findByOrderId(Long orderId);

    // 같은 환불을 동시에 두 번 처리하지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") Long id);

    // 구매자가 회수 택배비를 결제할 때: 두 번 눌러도 뒤 요청이 앞 요청의 결과(COMPLETED)를 보도록 처음부터 잠가서 읽는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.orderId = :orderId")
    Optional<PaymentRefund> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    /**
     * 관리자 실패 거래: 실패 상태(FAILED_*)이고 [from, to) 사이에 마지막으로 바뀐 반품 환불
     * from, to가 없으면 호출하는 쪽에서 아주 이른, 아주 늦은 시각을 넘긴다
     */
    @Query("""
            select r from PaymentRefund r
             where r.status in :statuses
               and r.updatedAt >= :from
               and r.updatedAt < :to
            """)
    Page<PaymentRefund> findFailures(@Param("statuses") Collection<PaymentRefundStatus> statuses,
                                     @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                     Pageable pageable);

    /** 관리자 실패 거래: findFailures와 같은 조건의 건수(유형을 합쳐 보여 줄 때 전체 건수와 읽을 범위를 먼저 정한다) */
    @Query("""
            select count(r) from PaymentRefund r
             where r.status in :statuses
               and r.updatedAt >= :from
               and r.updatedAt < :to
            """)
    long countFailures(@Param("statuses") Collection<PaymentRefundStatus> statuses,
                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
