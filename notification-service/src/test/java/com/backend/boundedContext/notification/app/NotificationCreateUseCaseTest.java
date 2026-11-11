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

        assertThat(saved()).singleElement().satisfies(notification -> {
            assertThat(notification.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(notification.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_PAID);
            assertThat(notification.isRead()).isFalse();
        });
        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("배송 시작 알림: 택배사·송장번호가 문구에 들어가고 따로도 저장됨")
    void createDeliveryStarted() {
        notificationFacade.createDeliveryStarted(MEMBER_ID, ORDER_ID, "CJ대한통운", "123456789012");

        assertThat(saved()).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.DELIVERY_STARTED);
            assertThat(notification.getMessage()).isEqualTo("상품이 발송되었습니다. (CJ대한통운 123456789012)");
            assertThat(notification.getCarrierName()).isEqualTo("CJ대한통운");
            assertThat(notification.getTrackingNumber()).isEqualTo("123456789012");
        });
    }

    @Test
    @DisplayName("배송 완료 알림: 구매자의 배송 완료 알림으로 저장됨")
    void createDeliveryCompleted() {
        notificationFacade.createDeliveryCompleted(MEMBER_ID, ORDER_ID);

        assertThat(saved()).singleElement().satisfies(notification -> {
            assertThat(notification.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(notification.getType()).isEqualTo(NotificationType.DELIVERY_COMPLETED);
        });
    }

    // 로컬 DB에 다른 알림이 있어도 이 테스트 회원의 알림만 봄
    private List<Notification> saved() {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getMemberId() == MEMBER_ID)
                .toList();
    }
}
