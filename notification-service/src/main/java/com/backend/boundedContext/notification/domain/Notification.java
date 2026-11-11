package com.backend.boundedContext.notification.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원별 알림.
 * 문구는 조회 시 조립하지 않고, 생성 시 완성한 문구를 저장함(받은 당시 내용이 그대로 남음).
 * 이벤트와 무관한 값으로 만들고, 컨슈머는 이벤트를 이 값으로 바꿔 부르기만 함.
 * 다른 컨텍스트(회원·주문)는 ID만 저장함.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification")
public class Notification extends BaseIdAndTime {

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String message;

    // 알림을 눌렀을 때 이동할 주문
    @Column(nullable = false)
    private Long orderId;

    // null이면 안 읽음
    private LocalDateTime readAt;

    private Notification(Long memberId, NotificationType type, String message, Long orderId) {
        this.memberId = memberId;
        this.type = type;
        this.message = message;
        this.orderId = orderId;
    }

    // 주문 완료(결제 성공)
    public static Notification orderPaid(Long memberId, Long orderId) {
        return new Notification(memberId, NotificationType.ORDER_PAID, "주문이 완료되어 스토어에서 상품 준비 중입니다.", orderId);
    }

    // 배송 시작(송장 등록). 택배사·송장번호를 문구에 포함함
    public static Notification deliveryStarted(Long memberId, Long orderId, String carrier, String trackingNumber) {
        return new Notification(memberId, NotificationType.DELIVERY_STARTED, "상품이 발송되었습니다. (%s %s)".formatted(carrier, trackingNumber), orderId);
    }

    // 배송 완료
    public static Notification deliveryCompleted(Long memberId, Long orderId) {
        return new Notification(memberId, NotificationType.DELIVERY_COMPLETED, "상품이 배송 완료되었습니다.", orderId);
    }

    public boolean isRead() {
        return readAt != null;
    }

    // 읽음 처리. 이미 읽은 알림은 처음 읽은 시각을 그대로 둠
    public void read(LocalDateTime now) {
        if (isRead()) return;
        this.readAt = now;
    }
}
