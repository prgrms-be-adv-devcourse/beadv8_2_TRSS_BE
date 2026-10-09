package com.backend.global.global;

import com.backend.global.eventPublisher.EventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * 스프링 빈이 아닌 곳(엔티티 등)에서 공통 빈을 꺼내 쓰기 위한 정적 보관소.
 */
@Configuration
public class GlobalConfig {
    private static EventPublisher eventPublisher;

    @Autowired
    public void setEventPublisher(EventPublisher eventPublisher) {
        GlobalConfig.eventPublisher = eventPublisher;
    }

    /**
     * 스프링 컨텍스트 없이(순수 단위 테스트 등) 호출하면 예외가 난다.
     * 엔티티 단위 테스트에서 이벤트를 발행하는 메서드를 검증하려면 @SpringBootTest를 쓴다.
     */
    public static EventPublisher getEventPublisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("EventPublisher가 아직 초기화되지 않았습니다. 스프링 컨텍스트 안에서 호출해야 합니다.");
        }
        return eventPublisher;
    }
}
