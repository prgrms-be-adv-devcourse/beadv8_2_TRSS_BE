package com.backend.boundedContext.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPolicyTest {

    @Test
    @DisplayName("보여 주는 기간의 시작은 지금에서 visibleDays일 전")
    void visibleSince() {
        NotificationPolicy policy = new NotificationPolicy(30);

        assertThat(policy.visibleSince(LocalDateTime.of(2026, 11, 11, 12, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 12, 0));
    }

    @Test
    @DisplayName("visibleDays가 1보다 작으면 앱 시작을 막음")
    void invalidVisibleDays() {
        assertThatThrownBy(() -> new NotificationPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
