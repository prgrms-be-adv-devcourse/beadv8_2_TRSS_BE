package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.MemberSignedUpEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * outbox_event 테이블 매핑을 실제 DB로 확인한다 (jsonb payload, event_id 유니크).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class OutboxEventRepositoryTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final List<OutboxEvent> saved = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll(saved);
    }

    @Test
    @DisplayName("봉투로 만든 이벤트는 PENDING, 재시도 0으로 저장되고 봉투 값이 그대로 남는다")
    void saveAndFind() {
        EventEnvelope<MemberSignedUpEvent> envelope = EventEnvelope.wrap(new MemberSignedUpEvent(42L));

        saved.add(outboxEventRepository.save(
                OutboxEvent.create(envelope, jsonMapper.writeValueAsString(envelope.payload()))));

        OutboxEvent found = outboxEventRepository.findByEventId(envelope.eventId()).orElseThrow();
        assertThat(found.getEventType()).isEqualTo("MemberSignedUpEvent");
        assertThat(found.getAggregateType()).isEqualTo("MEMBER");
        assertThat(found.getAggregateId()).isEqualTo(42L);
        assertThat(found.getOccurredAt()).isEqualTo(envelope.occurredAt().truncatedTo(ChronoUnit.MICROS));   // timestamp(6)
        assertThat(found.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(found.getRetryCount()).isZero();
        assertThat(found.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("jsonb로 저장한 payload를 읽어 원래 이벤트로 되돌릴 수 있다")
    void payloadRoundTrip() {
        EventEnvelope<MemberSignedUpEvent> envelope = EventEnvelope.wrap(new MemberSignedUpEvent(43L));
        saved.add(outboxEventRepository.save(
                OutboxEvent.create(envelope, jsonMapper.writeValueAsString(envelope.payload()))));

        String payload = outboxEventRepository.findByEventId(envelope.eventId()).orElseThrow().getPayload();

        JsonNode json = jsonMapper.readTree(payload);
        assertThat(json.get("memberId").asLong()).isEqualTo(43L);
        assertThat(jsonMapper.readValue(payload, MemberSignedUpEvent.class)).isEqualTo(envelope.payload());
    }

    @Test
    @DisplayName("같은 eventId는 두 번 저장할 수 없다")
    void duplicateEventId() {
        EventEnvelope<MemberSignedUpEvent> envelope = EventEnvelope.wrap(new MemberSignedUpEvent(44L));
        String payload = jsonMapper.writeValueAsString(envelope.payload());
        saved.add(outboxEventRepository.save(OutboxEvent.create(envelope, payload)));

        assertThatThrownBy(() -> outboxEventRepository.saveAndFlush(OutboxEvent.create(envelope, payload)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
