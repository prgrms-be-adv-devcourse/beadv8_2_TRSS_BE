package com.backend.boundedContext.notification.app.dto;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;

import java.time.LocalDateTime;

/**
 * 알림 목록 한 줄
 *
 * @param orderId        알림을 눌렀을 때 이동할 주문
 * @param productName    대표 상품명(주문의 첫 상품). 모르면 null
 * @param itemCount      주문 항목(상품 줄) 개수. 수량 합계가 아님. 모르면 null
 * @param carrierName    택배사 표시 이름. 배송 시작 알림만 있고 그 외는 null
 * @param trackingNumber 송장번호. 배송 시작 알림만 있고 그 외는 null
 * @param read           읽었으면 true
 * @param createdAt      알림이 생긴 시각
 */
public record NotificationDto(
        Long id,
        NotificationType type,
        String message,
        Long orderId,
        String productName,
        Integer itemCount,
        String carrierName,
        String trackingNumber,
        boolean read,
        LocalDateTime createdAt
) {
    public static NotificationDto from(Notification notification) {
        return new NotificationDto(notification.getId(), notification.getType(), notification.getMessage(),
                notification.getOrderId(), notification.getProductName(), notification.getItemCount(),
                notification.getCarrierName(), notification.getTrackingNumber(),
                notification.isRead(), notification.getCreatedAt());
    }
}
