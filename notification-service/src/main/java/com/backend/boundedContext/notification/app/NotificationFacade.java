package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.boundedContext.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final NotificationSubscribeUseCase notificationSubscribeUseCase;
    private final NotificationSendUseCase notificationSendUseCase;

    @Transactional
    public void createOrderPaid(Long memberId, Long orderId, String productName, Integer itemCount) {
        notificationCreateUseCase.createOrderPaid(memberId, orderId, productName, itemCount);
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
    public NotificationListDto getNotifications(Long memberId, NotificationType type, Long cursor, int size) {
        return notificationGetNotificationsUseCase.getNotifications(memberId, type, cursor, size);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long memberId) {
        return notificationGetUnreadCountUseCase.getUnreadCount(memberId);
    }

    // SSE 연결 등록. 연결 동안 트랜잭션을 잡지 않도록 트랜잭션 없이 둠
    public SseEmitter subscribe(Long memberId) {
        return notificationSubscribeUseCase.subscribe(memberId);
    }

    // 새 알림 SSE 전송. 커밋 뒤에 불려 트랜잭션 없이 둠
    public void sendCreated(Long memberId, NotificationDto notification) {
        notificationSendUseCase.sendCreated(memberId, notification);
    }

    // 안 읽은 개수 SSE 전송. 커밋 뒤에 불려 트랜잭션 없이 둠
    public void sendUnreadCount(Long memberId) {
        notificationSendUseCase.sendUnreadCount(memberId);
    }

    // 모든 SSE 연결에 heartbeat 전송
    public void sendHeartbeat() {
        notificationSendUseCase.sendHeartbeat();
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
