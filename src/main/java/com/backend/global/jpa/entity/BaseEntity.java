package com.backend.global.jpa.entity;

import com.backend.global.global.GlobalConfig;
import com.backend.standard.modelType.HasModelTypeCode;
import jakarta.persistence.MappedSuperclass;

import java.time.LocalDateTime;

/**
 * 모든 엔티티의 조상.
 * 엔티티는 이 클래스를 직접 상속하지 않고 {@link BaseIdAndTime} 또는 {@link BaseManualIdAndTime}을 상속한다.
 */
@MappedSuperclass
public abstract class BaseEntity implements HasModelTypeCode {

    public abstract Long getId();

    public abstract LocalDateTime getCreatedAt();

    public abstract LocalDateTime getUpdatedAt();

    /**
     * 엔티티 클래스 이름(예: "Order").
     */
    @Override
    public String getModelTypeCode() {
        return getClass().getSimpleName();
    }

    /**
     * 도메인 이벤트를 발행한다. 상태 변경 메서드 안에서 호출한다.
     * 받는 쪽은 @TransactionalEventListener(phase = AFTER_COMMIT)로 커밋 후에 처리한다.
     */
    protected void publishEvent(Object event) {
        GlobalConfig.getEventPublisher().publish(event);
    }
}
