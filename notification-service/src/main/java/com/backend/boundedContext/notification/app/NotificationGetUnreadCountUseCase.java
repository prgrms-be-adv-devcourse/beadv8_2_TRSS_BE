package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.NotificationPolicy;
import com.backend.boundedContext.notification.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationGetUnreadCountUseCase {

    private final NotificationRepository notificationRepository;
    private final NotificationPolicy notificationPolicy;

    // 안 읽은 알림 개수. 종 뱃지가 주기적으로 부름
    // 목록과 같은 기간(최근 visibleDays일)만 셈. 목록에 없는 오래된 알림 때문에 뱃지가 남지 않게 함
    public long getUnreadCount(Long memberId) {
        LocalDateTime since = notificationPolicy.visibleSince(LocalDateTime.now());
        return notificationRepository.countByMemberIdAndReadAtIsNullAndCreatedAtGreaterThanEqual(memberId, since);
    }
}
