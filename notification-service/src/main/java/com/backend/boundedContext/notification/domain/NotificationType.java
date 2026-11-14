package com.backend.boundedContext.notification.domain;

// 알림 시점. 한 주문에 시점별로 하나씩 생김
public enum NotificationType {
    ORDER_PAID,           // 주문 완료(결제 성공)
    DELIVERY_STARTED,     // 배송 시작(송장 등록)
    DELIVERY_COMPLETED    // 배송 완료
}
