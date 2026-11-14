package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationErrorCode;
import com.backend.boundedContext.notification.out.NotificationRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class NotificationReadUseCaseTest {

    private static final long MEMBER_ID = 1L;
    private static final long OTHER_MEMBER_ID = 2L;
    private static final LocalDateTime READ_BEFORE = LocalDateTime.of(2026, 11, 1, 9, 0);

    @Autowired
    private NotificationFacade notificationFacade;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("읽음 처리: 내 알림이 읽음이 되고 안 읽은 개수가 줄어듦")
    void read() {
        Notification notification = save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        save(Notification.deliveryCompleted(MEMBER_ID, 10L, null));

        notificationFacade.read(MEMBER_ID, notification.getId());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("읽음 처리: 다른 회원의 알림이면 404 NOTIFICATION_NOT_FOUND, 그 알림은 그대로 둠")
    void read_otherMember() {
        Notification notification = save(Notification.orderPaid(OTHER_MEMBER_ID, 20L, "립스틱", 1));

        assertThatThrownBy(() -> notificationFacade.read(MEMBER_ID, notification.getId()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND.resultCode());
        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("모두 읽음: 내 안 읽은 알림만 읽음이 되고, 이미 읽은 알림의 시각과 다른 회원 알림은 그대로 둠")
    void readAll() {
        save(Notification.orderPaid(MEMBER_ID, 10L, "립스틱", 1));
        save(Notification.deliveryStarted(MEMBER_ID, 10L, "CJ대한통운", "123", null));
        Notification alreadyRead = Notification.deliveryCompleted(MEMBER_ID, 10L, null);
        alreadyRead.read(READ_BEFORE);
        save(alreadyRead);
        save(Notification.orderPaid(OTHER_MEMBER_ID, 20L, "립스틱", 1));

        notificationFacade.readAll(MEMBER_ID);

        assertThat(notificationFacade.getUnreadCount(MEMBER_ID)).isZero();
        assertThat(notificationRepository.findById(alreadyRead.getId()).orElseThrow().getReadAt()).isEqualTo(READ_BEFORE);
        assertThat(notificationFacade.getUnreadCount(OTHER_MEMBER_ID)).isEqualTo(1L);
    }

    private Notification save(Notification notification) {
        return notificationRepository.saveAndFlush(notification);
    }
}
