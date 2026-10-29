package com.backend.shared.event;

/**
 * 테스트 전용 이벤트. 다른 리스너가 반응하지 않는다.
 * 릴레이는 com.backend.shared 아래 이벤트만 알므로 테스트 소스의 같은 패키지에 둔다(운영 클래스패스에는 없음).
 */
public record OutboxTestEvent(Long testId) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "TEST";
    }

    @Override
    public Long aggregateId() {
        return testId;
    }
}
