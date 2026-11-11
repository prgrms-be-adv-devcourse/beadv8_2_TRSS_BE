package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationErrorCode;
import com.backend.boundedContext.notification.out.NotificationRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationReadUseCase {
    private final NotificationRepository notificationRepository;

    // 알림 하나 읽음 처리. 없거나 다른 회원의 알림이면 404, 이미 읽은 알림이면 그대로 성공함
    public void read(Long memberId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .orElseThrow(() -> new DomainException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.read(LocalDateTime.now());
    }

    // 안 읽은 내 알림을 모두 읽음 처리함. 이미 읽은 알림은 처음 읽은 시각을 그대로 둠
    public void readAll(Long memberId) {
        notificationRepository.readAllByMemberId(memberId, LocalDateTime.now());
    }
}
