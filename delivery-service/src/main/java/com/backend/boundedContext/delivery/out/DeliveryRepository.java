package com.backend.boundedContext.delivery.out;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.shared.delivery.dto.DeliveryStatus;

import jakarta.persistence.LockModeType;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    // 같은 주문의 배송이 이미 있는지 (eventId가 다른 중복 결제 이벤트)
    boolean existsByOrderId(Long orderId);

    Optional<Delivery> findByOrderId(Long orderId);

    // 송장 등록·수정 경합 방지 -> 배송 행을 잠가서 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findByIdForUpdate(@Param("id") Long id);

    // 다른 배송 건에 같은 택배사·송장번호가 있는지 (송장번호는 정규화한 값)
    boolean existsByCourierAndTrackingNumberAndIdNot(Courier courier, String trackingNumber, Long id);

    // 항목은 Delivery.items의 @BatchSize로 한 번에 읽는다
    Page<Delivery> findByStatus(DeliveryStatus status, Pageable pageable);
}
