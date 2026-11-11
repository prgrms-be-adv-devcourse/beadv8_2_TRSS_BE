package com.backend.boundedContext.notification.out;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("알림을 저장하면 notification 테이블에 그대로 들어가고 생성 시각이 채워짐")
    void save() {
        Notification paid = Notification.orderPaid(1L, 10L, "라이트 윈드브레이커", 3);
        Notification saved = notificationRepository.saveAndFlush(
                Notification.deliveryStarted(1L, 10L, "CJ대한통운", "123456789012", paid));

        Notification found = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getMemberId()).isEqualTo(1L);
        assertThat(found.getOrderId()).isEqualTo(10L);
        assertThat(found.getType()).isEqualTo(NotificationType.DELIVERY_STARTED);
        assertThat(found.getMessage()).isEqualTo("상품이 발송되었습니다. (CJ대한통운 123456789012)");
        assertThat(found.getCarrierName()).isEqualTo("CJ대한통운");
        assertThat(found.getTrackingNumber()).isEqualTo("123456789012");
        assertThat(found.getProductName()).isEqualTo("라이트 윈드브레이커");
        assertThat(found.getItemCount()).isEqualTo(3);
        assertThat(found.getReadAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
