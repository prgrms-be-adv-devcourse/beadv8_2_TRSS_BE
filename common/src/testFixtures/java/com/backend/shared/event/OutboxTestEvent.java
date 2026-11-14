package com.backend.shared.event;

/**
 * 테스트 전용 이벤트. 다른 리스너가 반응하지 않는다.
 * 릴레이는 com.backend.shared 아래 이벤트만 알므로 같은 패키지에 둔다. common의 testFixtures라 각 모듈 테스트에서 함께 쓰고, 운영 클래스패스에는 없다.
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
