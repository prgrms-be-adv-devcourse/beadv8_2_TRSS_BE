package com.backend.global.jpa.entity;

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

    // 엔티티 클래스 이름
    @Override
    public String getModelTypeCode() {
        return getClass().getSimpleName();
    }
}
