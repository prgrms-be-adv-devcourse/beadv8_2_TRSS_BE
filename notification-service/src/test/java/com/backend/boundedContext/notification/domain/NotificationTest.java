package com.backend.boundedContext.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0);

    @Test
    @DisplayName("주문 완료 알림: 상품 준비 중 문구로 안 읽은 상태로 생김")
    void orderPaid() {
        Notification n = Notification.orderPaid(1L, 10L);

        assertThat(n.getMemberId()).isEqualTo(1L);
        assertThat(n.getOrderId()).isEqualTo(10L);
        assertThat(n.getType()).isEqualTo(NotificationType.ORDER_PAID);
        assertThat(n.getMessage()).isEqualTo("주문이 완료되어 스토어에서 상품 준비 중입니다.");
        assertThat(n.getCarrierName()).isNull();
        assertThat(n.getTrackingNumber()).isNull();
        assertThat(n.isRead()).isFalse();
    }

    @Test
    @DisplayName("배송 시작 알림: 택배사·송장번호가 문구에 들어가고 따로도 저장됨")
    void deliveryStarted() {
        Notification n = Notification.deliveryStarted(1L, 10L, "CJ대한통운", "123456789012");

        assertThat(n.getType()).isEqualTo(NotificationType.DELIVERY_STARTED);
        assertThat(n.getMessage()).isEqualTo("상품이 발송되었습니다. (CJ대한통운 123456789012)");
        assertThat(n.getCarrierName()).isEqualTo("CJ대한통운");
        assertThat(n.getTrackingNumber()).isEqualTo("123456789012");
    }

    @Test
    @DisplayName("배송 완료 알림: 배송 완료 문구로 생김")
    void deliveryCompleted() {
        Notification n = Notification.deliveryCompleted(1L, 10L);

        assertThat(n.getType()).isEqualTo(NotificationType.DELIVERY_COMPLETED);
        assertThat(n.getMessage()).isEqualTo("상품이 배송 완료되었습니다.");
        assertThat(n.getCarrierName()).isNull();
        assertThat(n.getTrackingNumber()).isNull();
    }

    @Test
    @DisplayName("읽음 처리: 다시 읽어도 처음 읽은 시각을 그대로 둠")
    void read() {
        Notification n = Notification.orderPaid(1L, 10L);

        n.read(NOW);
        n.read(NOW.plusMinutes(5));

        assertThat(n.isRead()).isTrue();
        assertThat(n.getReadAt()).isEqualTo(NOW);
    }
}
