package com.backend.global.eventPublisher;

import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventTypes;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.MemberSignedUpEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 도메인 이벤트 발행기.
 * 호출한 쪽 트랜잭션 안에서 이벤트를 outbox_event에 저장한다. 비즈니스 변경이 롤백되면 이벤트도 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class EventPublisher {

    /**
     * 아직 릴레이로 옮기지 않은 이벤트. Outbox 도입 전처럼 호출한 쪽 트랜잭션 안에서 바로 스프링 이벤트로 발행한다.
     */
    private static final Set<Class<? extends DomainEvent>> DIRECT_PUBLISH_EVENTS =
            Set.of(MemberSignedUpEvent.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventTypes outboxEventTypes;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 이벤트를 outbox에 저장한다.
     * 아직 릴레이로 옮기지 않은 이벤트는 바로 스프링 이벤트로 발행하고, 릴레이가 다시 보내지 않게 PUBLISHED로 저장한다.
     *
     * @throws IllegalStateException    쓰기 트랜잭션 밖에서 호출한 경우. 저장할 트랜잭션이 없으면 이벤트가 유실된다
     * @throws IllegalArgumentException 릴레이가 되돌릴 수 없는 이벤트인 경우(com.backend.shared 밖에 둔 이벤트)
     */
    public void publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "이벤트는 쓰기 트랜잭션 안에서 발행해야 합니다: " + event.getClass().getSimpleName());
        }

        // 저장만 되고 릴레이에서 FAILED가 되는 대신, 발행하는 순간 알 수 있게 막는다
        if (outboxEventTypes.find(event.getClass().getSimpleName()).filter(event.getClass()::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "릴레이가 모르는 이벤트입니다. com.backend.shared 아래에 두어야 합니다: " + event.getClass().getName());
        }

        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(event));

        if (DIRECT_PUBLISH_EVENTS.contains(event.getClass())) {
            applicationEventPublisher.publishEvent(event);
            outbox.markPublished(LocalDateTime.now());
        }

        outboxEventRepository.save(outbox);
    }
}
