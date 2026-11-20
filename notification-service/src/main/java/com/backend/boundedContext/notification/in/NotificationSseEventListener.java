package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationCreatedEvent;
import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.boundedContext.notification.app.NotificationReadEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 알림 저장·읽음 처리가 커밋된 뒤 SSE로 보냄. 롤백되면 보내지 않음
 * 전송이 실패해도 알림은 이미 저장되어 있어 로그만 남김. 구매자는 목록 조회로 확인함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSseEventListener {

    private final NotificationFacade notificationFacade;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationCreatedEvent event) {
        try {
            notificationFacade.sendCreated(event.memberId(), event.notification());
        } catch (RuntimeException e) {
            log.warn("알림 SSE 전송 실패: memberId={}, notificationId={}", event.memberId(), event.notification().id(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRead(NotificationReadEvent event) {
        try {
            notificationFacade.sendUnreadCount(event.memberId());
        } catch (RuntimeException e) {
            log.warn("안 읽은 개수 SSE 전송 실패: memberId={}", event.memberId(), e);
        }
    }
}
