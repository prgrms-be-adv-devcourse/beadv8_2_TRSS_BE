package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.out.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class NotificationGetNotificationsUseCaseTest {

    private static final long MEMBER_ID = 1L;
    private static final long OTHER_MEMBER_ID = 2L;

    @Autowired
    private NotificationFacade notificationFacade;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("알림 목록: 내 알림만 최신순으로 size개씩, nextCursor로 이어서 조회함")
    void getNotifications_cursor() {
        Notification first = save(Notification.orderPaid(MEMBER_ID, 10L));
        Notification second = save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123"));
        Notification third = save(Notification.deliveryCompleted(MEMBER_ID, 10L));
        save(Notification.orderPaid(OTHER_MEMBER_ID, 20L));

        NotificationListDto page1 = notificationFacade.getNotifications(MEMBER_ID, null, 2);
        assertThat(ids(page1)).containsExactly(third.getId(), second.getId());
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isEqualTo(second.getId());

        NotificationListDto page2 = notificationFacade.getNotifications(MEMBER_ID, page1.nextCursor(), 2);
        assertThat(ids(page2)).containsExactly(first.getId());
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    @DisplayName("알림 목록: 알림이 없으면 빈 목록, 다음 페이지 없음")
    void getNotifications_empty() {
        NotificationListDto result = notificationFacade.getNotifications(MEMBER_ID, null, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("알림 목록: 배송 시작 알림만 택배사·송장번호가 내려가고 나머지는 null")
    void getNotifications_trackingFields() {
        save(Notification.orderPaid(MEMBER_ID, 10L));
        save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123456789012"));

        List<NotificationDto> items = notificationFacade.getNotifications(MEMBER_ID, null, 20).items();
        assertThat(items).extracting(NotificationDto::carrierName, NotificationDto::trackingNumber)
                .containsExactly(tuple("CJ대한통운", "123456789012"), tuple(null, null));
    }

    @Test
    @DisplayName("안 읽은 개수: 내 알림 중 읽지 않은 것만 셈")
    void getUnreadCount() {
        save(Notification.orderPaid(MEMBER_ID, 10L));
        Notification read = Notification.deliveryCompleted(MEMBER_ID, 10L);
        read.read(LocalDateTime.of(2026, 11, 6, 12, 0));
        save(read);
        save(Notification.orderPaid(OTHER_MEMBER_ID, 20L));

        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isEqualTo(1L);
    }

    private Notification save(Notification notification) {
        return notificationRepository.save(notification);
    }

    private static List<Long> ids(NotificationListDto list) {
        return list.items().stream().map(NotificationDto::id).toList();
    }
}
