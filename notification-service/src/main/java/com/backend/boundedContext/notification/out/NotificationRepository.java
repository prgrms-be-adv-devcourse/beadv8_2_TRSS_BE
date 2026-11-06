package com.backend.boundedContext.notification.out;

import com.backend.boundedContext.notification.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 회원의 알림 중 cursor보다 id가 작은 것을 최신순으로 limit개 (idx_notification_member_id)
    List<Notification> findByMemberIdAndIdLessThanOrderByIdDesc(Long memberId, Long cursor, Limit limit);

    // 안 읽은 알림 개수 (idx_notification_member_unread)
    long countByMemberIdAndReadAtIsNull(Long memberId);
}
