package com.backend.boundedContext.notification.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// 알림 컨텍스트 정책 값 (custom.notification.*)
@Component
@Getter
public class NotificationPolicy {

    // 목록·안 읽은 개수에 보여 주는 기간(일). 이보다 오래된 알림은 지우지 않고 조회에서만 뺌
    private final int visibleDays;

    public NotificationPolicy(@Value("${custom.notification.visibleDays}") int visibleDays) {
        if (visibleDays < 1) throw new IllegalArgumentException("custom.notification.visibleDays는 1 이상이어야 합니다: " + visibleDays);
        this.visibleDays = visibleDays;
    }

    // 이 시각 이후에 생긴 알림만 보여 줌
    public LocalDateTime visibleSince(LocalDateTime now) {
        return now.minusDays(visibleDays);
    }
}
