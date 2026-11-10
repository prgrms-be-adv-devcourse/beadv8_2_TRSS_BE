package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationGetUnreadCountUseCase {

    private final NotificationRepository notificationRepository;

    // 안 읽은 알림 개수. 종 뱃지가 주기적으로 부름
    public long getUnreadCount(Long memberId) {
        return notificationRepository.countByMemberIdAndReadAtIsNull(memberId);
    }
}
