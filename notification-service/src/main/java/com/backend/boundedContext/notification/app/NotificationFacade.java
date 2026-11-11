package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 컨텍스트의 유일한 입구
 *
 * in(컨트롤러·이벤트 리스너)은 이 클래스만 호출함.
 * 구현 로직은 UseCase에 두고 여기서는 트랜잭션 경계를 정해 위임만 함.
 */
@Component
@RequiredArgsConstructor
public class NotificationFacade {

    private final NotificationCreateUseCase notificationCreateUseCase;
    private final NotificationGetNotificationsUseCase notificationGetNotificationsUseCase;
    private final NotificationGetUnreadCountUseCase notificationGetUnreadCountUseCase;
    private final NotificationReadUseCase notificationReadUseCase;

    @Transactional
    public void createOrderPaid(Long memberId, Long orderId) {
        notificationCreateUseCase.createOrderPaid(memberId, orderId);
    }

    @Transactional
    public void createDeliveryStarted(Long memberId, Long orderId, String carrierName, String trackingNumber) {
        notificationCreateUseCase.createDeliveryStarted(memberId, orderId, carrierName, trackingNumber);
    }

    @Transactional
    public void createDeliveryCompleted(Long memberId, Long orderId) {
        notificationCreateUseCase.createDeliveryCompleted(memberId, orderId);
    }

    @Transactional(readOnly = true)
    public NotificationListDto getNotifications(Long memberId, Long cursor, int size) {
        return notificationGetNotificationsUseCase.getNotifications(memberId, cursor, size);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long memberId) {
        return notificationGetUnreadCountUseCase.getUnreadCount(memberId);
    }

    @Transactional
    public void read(Long memberId, Long notificationId) {
        notificationReadUseCase.read(memberId, notificationId);
    }

    @Transactional
    public void readAll(Long memberId) {
        notificationReadUseCase.readAll(memberId);
    }
}
