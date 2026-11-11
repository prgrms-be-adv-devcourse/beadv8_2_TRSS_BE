package com.backend.boundedContext.notification.app.dto;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;

import java.time.LocalDateTime;

/**
 * 알림 목록 한 줄
 *
 * @param orderId   알림을 눌렀을 때 이동할 주문
 * @param read      읽었으면 true
 * @param createdAt 알림이 생긴 시각
 */
public record NotificationDto(
        Long id,
        NotificationType type,
        String message,
        Long orderId,
        boolean read,
        LocalDateTime createdAt
) {
    public static NotificationDto from(Notification notification) {
        return new NotificationDto(notification.getId(), notification.getType(), notification.getMessage(),
                notification.getOrderId(), notification.isRead(), notification.getCreatedAt());
    }
}
