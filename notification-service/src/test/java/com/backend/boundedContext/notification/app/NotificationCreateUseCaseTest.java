package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;
import com.backend.boundedContext.notification.out.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class NotificationCreateUseCaseTest {

    private static final long MEMBER_ID = 1L;
    private static final long ORDER_ID = 10L;

    @Autowired
    private NotificationFacade notificationFacade;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("주문 완료 알림: 구매자의 안 읽은 알림으로 저장됨")
    void createOrderPaid() {
        notificationFacade.createOrderPaid(MEMBER_ID, ORDER_ID);

        List<Notification> saved = notificationRepository.findAll();
        assertThat(saved).singleElement().satisfies(notification -> {
            assertThat(notification.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(notification.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_PAID);
            assertThat(notification.isRead()).isFalse();
        });
        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isEqualTo(1L);
    }
}
