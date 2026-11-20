package com.backend.shared.event;

/**
 * 컨텍스트 밖으로 알리는 이벤트 record가 구현하는 계약.
 */
public interface DomainEvent {

    // 이벤트가 생긴 집합 종류. 대문자 단수형(예: {@code ORDER}, {@code MEMBER})
    String aggregateType();

    // 이벤트가 생긴 집합의 ID
    Long aggregateId();
}
