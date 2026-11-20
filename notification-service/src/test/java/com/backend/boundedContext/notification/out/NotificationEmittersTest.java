package com.backend.boundedContext.notification.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmittersTest {

    private final NotificationEmitters emitters = new NotificationEmitters();

    @Test
    @DisplayName("회원마다 연결을 여러 개 둘 수 있음")
    void add_multipleTabs() {
        emitters.add(1L, new SseEmitter());
        emitters.add(1L, new SseEmitter());
        emitters.add(2L, new SseEmitter());

        assertThat(emitters.count(1L)).isEqualTo(2);
        assertThat(emitters.count(2L)).isEqualTo(1);
    }

    @Test
    @DisplayName("보내지 못한 연결은 지우고, 나머지 연결은 그대로 둠")
    void send_removesBrokenEmitter() {
        SseEmitter broken = new SseEmitter();
        emitters.add(1L, broken);
        emitters.add(1L, new SseEmitter());
        broken.complete();   // 끝난 연결에 보내면 IllegalStateException

        emitters.send(1L, NotificationEmitters.UNREAD_COUNT, 3L);

        assertThat(emitters.count(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("연결이 없는 회원에게 보내면 아무 일도 없음")
    void send_noEmitter() {
        emitters.send(1L, NotificationEmitters.UNREAD_COUNT, 3L);

        assertThat(emitters.count(1L)).isZero();
    }

    @Test
    @DisplayName("heartbeat는 모든 회원의 연결로 보내고, 보내지 못한 연결만 지움")
    void sendHeartbeat_removesBrokenEmitters() {
        SseEmitter broken = new SseEmitter();
        emitters.add(1L, broken);
        emitters.add(1L, new SseEmitter());
        emitters.add(2L, new SseEmitter());
        broken.complete();

        emitters.sendHeartbeat();

        assertThat(emitters.count(1L)).isEqualTo(1);
        assertThat(emitters.count(2L)).isEqualTo(1);
    }
}
