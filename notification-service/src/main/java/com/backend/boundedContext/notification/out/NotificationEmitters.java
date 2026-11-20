package com.backend.boundedContext.notification.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 회원별 SSE 연결 저장소
 *
 * 탭마다 연결이 하나씩 생기므로 회원당 여러 연결을 둠.
 * 서버 메모리에만 두므로 서버가 한 대일 때만 모든 연결에 전달됨.
 */
@Slf4j
@Component
public class NotificationEmitters {

    public static final String NOTIFICATION = "notification";
    public static final String UNREAD_COUNT = "unread-count";

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // 연결을 등록하고, 연결이 끝나면(완료·시간 초과·오류) 저장소에서 지움
    public void add(Long memberId, SseEmitter emitter) {
        // 꺼내기와 추가를 compute로 묶음. 나누면 그 사이 remove가 키를 지워 새 연결이 빠질 수 있음
        emitters.compute(memberId, (id, set) -> {
            Set<SseEmitter> result = set == null ? ConcurrentHashMap.newKeySet() : set;
            result.add(emitter);
            return result;
        });
        emitter.onCompletion(() -> remove(memberId, emitter));
        // 시간 초과도 정상 종료로 끝냄. complete가 없으면 Spring이 AsyncRequestTimeoutException으로 넘김
        emitter.onTimeout(() -> {
            remove(memberId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(memberId, emitter));
    }

    // 회원의 모든 연결로 이벤트를 보냄
    public void send(Long memberId, String name, Object data) {
        for (SseEmitter emitter : emitters.getOrDefault(memberId, Set.of())) {
            send(memberId, emitter, SseEmitter.event().name(name).data(data));
        }
    }

    // 모든 연결로 SSE 주석(:heartbeat)을 보냄. 프론트는 무시하고, 끊긴 연결은 이때 정리됨
    public void sendHeartbeat() {
        emitters.forEach((memberId, set) -> set.forEach(emitter ->
                send(memberId, emitter, SseEmitter.event().comment("heartbeat"))));
    }

    public int count(Long memberId) {
        return emitters.getOrDefault(memberId, Set.of()).size();
    }

    // 보내지 못한 연결은 끊긴 것으로 보고 지움
    private void send(Long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 전송 실패로 연결을 정리함: memberId={}", memberId, e);
            remove(memberId, emitter);
        }
    }

    // 목록이 비면 키까지 지움
    private void remove(Long memberId, SseEmitter emitter) {
        emitters.computeIfPresent(memberId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
