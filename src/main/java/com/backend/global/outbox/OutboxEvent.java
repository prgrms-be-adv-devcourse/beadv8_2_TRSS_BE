package com.backend.global.outbox;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.event.EventEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 발행을 기다리는 이벤트 한 건. {@link EventEnvelope} 하나와 1:1로 대응한다.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseIdAndTime {

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 30)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    /** payload record를 JSON으로 바꾼 값 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(nullable = false)
    private int retryCount;

    private LocalDateTime publishedAt;

    /** 봉투와 JSON으로 바꾼 payload로 미발행(PENDING) 이벤트를 만든다. */
    public static OutboxEvent create(EventEnvelope<?> envelope, String payloadJson) {
        OutboxEvent event = new OutboxEvent();
        event.eventId = envelope.eventId();
        event.eventType = envelope.eventType();
        event.occurredAt = envelope.occurredAt();
        event.aggregateType = envelope.aggregateType();
        event.aggregateId = envelope.aggregateId();
        event.payload = payloadJson;
        event.status = OutboxEventStatus.PENDING;
        event.retryCount = 0;
        return event;
    }

    /** 릴레이가 발행을 마쳤다. */
    public void markPublished(LocalDateTime now) {
        status = OutboxEventStatus.PUBLISHED;
        publishedAt = now;
    }

    /** 발행에 실패했다. 실패 횟수가 한도에 닿으면 FAILED로 두고 더 이상 발행하지 않는다. */
    public void recordFailure(int maxRetryCount) {
        retryCount++;
        if (retryCount >= maxRetryCount) {
            status = OutboxEventStatus.FAILED;
        }
    }
}
