package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationPolicy;
import com.backend.boundedContext.notification.domain.NotificationType;
import com.backend.boundedContext.notification.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationGetNotificationsUseCase {

    private final NotificationRepository notificationRepository;
    private final NotificationPolicy notificationPolicy;

    /**
     * 내 알림 목록(최신순). cursor가 없으면 처음부터, 있으면 그 id보다 오래된 알림부터 조회함.
     * 폴링 중 새 알림이 생겨도 다음 페이지가 밀리지 않도록 offset 대신 id 커서를 씀.
     * 다음 페이지가 있는지 보려고 size보다 1개 더 조회함.
     * type이 있으면 그 유형만, 없으면 전체를 조회함.
     * 최근 visibleDays일 안에 생긴 알림만 보여 줌.
     */
    public NotificationListDto getNotifications(Long memberId, NotificationType type, Long cursor, int size) {
        // 첫 페이지는 모든 id보다 큰 값을 커서로 써서 처음부터 조회함
        long before = cursor != null ? cursor : Long.MAX_VALUE;
        LocalDateTime since = notificationPolicy.visibleSince(LocalDateTime.now());
        Limit limit = Limit.of(size + 1);
        List<Notification> found = type == null
                ? notificationRepository.findByMemberIdAndCreatedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(memberId, since, before, limit)
                : notificationRepository.findByMemberIdAndTypeAndCreatedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(memberId, type, since, before, limit);

        boolean hasNext = found.size() > size;
        List<NotificationDto> items = found.stream().limit(size).map(NotificationDto::from).toList();
        Long nextCursor = hasNext ? items.getLast().id() : null;

        return new NotificationListDto(items, nextCursor, hasNext);
    }
}
