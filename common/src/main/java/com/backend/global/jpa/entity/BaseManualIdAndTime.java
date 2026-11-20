package com.backend.global.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * ID를 코드에서 직접 지정하는 엔티티의 부모.
 * 다른 컨텍스트의 ID를 그대로 써야 하는 경우에만 사용한다. 대부분의 엔티티는 {@link BaseIdAndTime}을 쓴다.
 *
 * <p>ID가 처음부터 있으므로 Spring Data의 save()는 ID만 보고 새 엔티티인지 알 수 없어,
 * merge(SELECT 후 INSERT)를 부른다. 저장 전에는 생성 시각이 비어 있으므로 그것으로 새 엔티티를 판단해 바로 persist(INSERT)하게 한다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class BaseManualIdAndTime extends BaseEntity implements Persistable<Long> {

    @Id
    private Long id;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected BaseManualIdAndTime(Long id) {
        this.id = id;
    }

    /** 아직 저장하지 않았으면 true. 생성 시각은 저장할 때(auditing) 채워진다 */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
