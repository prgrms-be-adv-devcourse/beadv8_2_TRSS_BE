package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;
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
        Notification first = save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        Notification second = save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123", null));
        Notification third = save(Notification.deliveryCompleted(MEMBER_ID, 10L, null));
        save(Notification.orderPaid(OTHER_MEMBER_ID, 20L, "립스틱", 1));

        NotificationListDto page1 = notificationFacade.getNotifications(MEMBER_ID, null, null, 2);
        assertThat(ids(page1)).containsExactly(third.getId(), second.getId());
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isEqualTo(second.getId());

        NotificationListDto page2 = notificationFacade.getNotifications(MEMBER_ID, null, page1.nextCursor(), 2);
        assertThat(ids(page2)).containsExactly(first.getId());
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    @DisplayName("알림 목록: type을 넘기면 그 유형만 최신순으로, 같은 type으로 nextCursor 이어서 조회함")
    void getNotifications_type() {
        Notification paid1 = save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123", paid1));
        Notification paid2 = save(Notification.orderPaid(MEMBER_ID, 11L, "틴트", 2));
        save(Notification.deliveryCompleted(MEMBER_ID, 10L, paid1));
        Notification paid3 = save(Notification.orderPaid(MEMBER_ID, 12L, "쿠션", 1));
        save(Notification.orderPaid(OTHER_MEMBER_ID, 20L, "립스틱", 1));

        NotificationListDto page1 = notificationFacade.getNotifications(MEMBER_ID, NotificationType.ORDER_PAID, null, 2);
        assertThat(ids(page1)).containsExactly(paid3.getId(), paid2.getId());
        assertThat(page1.hasNext()).isTrue();

        NotificationListDto page2 = notificationFacade.getNotifications(MEMBER_ID, NotificationType.ORDER_PAID, page1.nextCursor(), 2);
        assertThat(ids(page2)).containsExactly(paid1.getId());
        assertThat(page2.hasNext()).isFalse();
    }

    @Test
    @DisplayName("안 읽은 개수: type 필터와 관계없이 모든 유형의 안 읽은 알림을 셈")
    void getUnreadCount_allTypes() {
        save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123", null));
        save(Notification.deliveryCompleted(MEMBER_ID, 10L, null));

        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isEqualTo(3L);
    }

    @Test
    @DisplayName("알림 목록: 알림이 없으면 빈 목록, 다음 페이지 없음")
    void getNotifications_empty() {
        NotificationListDto result = notificationFacade.getNotifications(MEMBER_ID, null, null, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("알림 목록: 상품명·개수가 내려가고, 택배사·송장번호는 배송 시작 알림만 있음")
    void getNotifications_displayFields() {
        Notification paid = save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123456789012", paid));

        List<NotificationDto> items = notificationFacade.getNotifications(MEMBER_ID, null, null, 20).items();
        assertThat(items).extracting(NotificationDto::productName, NotificationDto::itemCount,
                        NotificationDto::carrierName, NotificationDto::trackingNumber)
                .containsExactly(tuple("립스틱", 1, "CJ대한통운", "123456789012"), tuple("립스틱", 1, null, null));
    }

    @Test
    @DisplayName("안 읽은 개수: 내 알림 중 읽지 않은 것만 셈")
    void getUnreadCount() {
        save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        Notification read = Notification.deliveryCompleted(MEMBER_ID, 10L, null);
        read.read(LocalDateTime.of(2026, 11, 6, 12, 0));
        save(read);
        save(Notification.orderPaid(OTHER_MEMBER_ID, 20L, "립스틱", 1));

        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isEqualTo(1L);
    }

    private Notification save(Notification notification) {
        return notificationRepository.save(notification);
    }

    private static List<Long> ids(NotificationListDto list) {
        return list.items().stream().map(NotificationDto::id).toList();
    }
}
