package com.backend.boundedContext.notification.out;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 회원의 알림 중 since 이후에 생겼고 cursor보다 id가 작은 것을 최신순으로 limit개 (idx_notification_member_id)
    List<Notification> findByMemberIdAndCreatedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(Long memberId, LocalDateTime since, Long cursor, Limit limit);

    // 위와 같고 유형 하나만 (idx_notification_member_id로 회원 범위를 좁힌 뒤 type을 거름)
    List<Notification> findByMemberIdAndTypeAndCreatedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(Long memberId, NotificationType type, LocalDateTime since, Long cursor, Limit limit);

    // since 이후에 생긴 안 읽은 알림 개수 (idx_notification_member_unread)
    long countByMemberIdAndReadAtIsNullAndCreatedAtGreaterThanEqual(Long memberId, LocalDateTime since);

    // 회원의 같은 주문 알림 중 해당 유형 하나. 배송 알림이 결제 완료 알림의 상품명·개수를 가져올 때 씀 (idx_notification_member_id)
    Optional<Notification> findFirstByMemberIdAndOrderIdAndType(Long memberId, Long orderId, NotificationType type);

    // 본인 알림만 찾음. 다른 회원의 알림이면 empty
    Optional<Notification> findByIdAndMemberId(Long id, Long memberId);

    // 안 읽은 알림을 한 번에 읽음 처리함. 벌크 update는 Auditing이 돌지 않아 updated_at도 직접 채움
    @Modifying(clearAutomatically = true)
    @Query("""
            update Notification n
               set n.readAt = :now, n.updatedAt = :now
             where n.memberId = :memberId
               and n.readAt is null
            """)
    int readAllByMemberId(Long memberId, LocalDateTime now);
}
