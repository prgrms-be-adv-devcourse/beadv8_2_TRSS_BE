package com.backend.boundedContext.delivery.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    // 콜백 → 확인 요청 기록. 이미 요청이 있어도 항상 now로 덮어쓴다.
    @Modifying
    @Query("""
            update Delivery d set d.trackingCheckRequestedAt = :now
            where d.courier = :courier and d.trackingNumber = :trackingNumber
              and d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
            """)
    int requestTrackingCheck(@Param("courier") Courier courier, @Param("trackingNumber") String trackingNumber,
                             @Param("now") LocalDateTime now);

    // 안전망: 발송 후 오래 SHIPPED인 배송 건에 확인 요청을 남긴다
    @Modifying
    @Query("""
            update Delivery d set d.trackingCheckRequestedAt = :now
            where d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
              and d.shippedAt < :shippedBefore
              and d.trackingCheckRequestedAt is null
            """)
    int requestSafetyCheck(@Param("shippedBefore") LocalDateTime shippedBefore, @Param("now") LocalDateTime now);

    // 확인 요청 지우기. 조회를 시작한 뒤에 온 요청은 남긴다
    @Modifying
    @Query("""
            update Delivery d set d.trackingCheckRequestedAt = null
            where d.id = :id and d.trackingCheckRequestedAt <= :startedAt
            """)
    int clearTrackingCheckRequest(@Param("id") Long id, @Param("startedAt") LocalDateTime startedAt);

    // 확인 워커 대상: 확인 요청이 있는 SHIPPED.
    @Query("""
            select d.id from Delivery d
            where d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
              and d.trackingCheckRequestedAt is not null and d.id > :afterId
            order by d.id
            """)
    List<Long> findTrackingCheckTargetIds(@Param("afterId") Long afterId, Limit limit);

    // 새 웹훅 등록 대상: 아직 등록하지 않았거나 등록에 실패한 SHIPPED
    @Query("""
            select d.id from Delivery d
            where d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
              and d.webhookExpiresAt is null and d.id > :afterId
            order by d.id
            """)
    List<Long> findWebhookRegisterTargetIds(@Param("afterId") Long afterId, Limit limit);

    // 웹훅 재등록 대상: 만료가 threshold 전인 SHIPPED
    @Query("""
            select d.id from Delivery d
            where d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
              and d.webhookExpiresAt < :threshold and d.id > :afterId
            order by d.id
            """)
    List<Long> findWebhookRenewTargetIds(@Param("threshold") LocalDateTime threshold, @Param("afterId") Long afterId, Limit limit);

    // 활성 웹훅 수: 만료 전인 SHIPPED
    @Query("""
            select count(d) from Delivery d
            where d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
              and d.webhookExpiresAt > :now
            """)
    long countActiveWebhooks(@Param("now") LocalDateTime now);

    // 웹훅 등록 성공 기록. 등록하는 동안 송장이 바뀌었거나 배송이 끝났으면 0
    @Modifying
    @Query("""
            update Delivery d set d.webhookExpiresAt = :expiresAt
            where d.id = :id and d.courier = :courier and d.trackingNumber = :trackingNumber
              and d.status = com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED
            """)
    int markWebhookRegistered(@Param("id") Long id, @Param("courier") Courier courier,
                              @Param("trackingNumber") String trackingNumber, @Param("expiresAt") LocalDateTime expiresAt);
}
