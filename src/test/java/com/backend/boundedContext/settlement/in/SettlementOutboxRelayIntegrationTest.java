package com.backend.boundedContext.settlement.in;

import com.backend.boundedContext.settlement.app.SettlementCreateCandidatesUseCase;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.global.outbox.OutboxRelay;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.event.OrderConfirmedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;

/**
 * OrderConfirmedEvent가 Outbox 릴레이로 정산 후보까지 전달되는지 실제 DB로 확인한다(TRSS-121 4-1).
 * 스케줄러는 끄고 릴레이를 직접 호출한다. 로컬 DB에 남아 있던 다른 PENDING 이벤트도 함께 발행될 수 있다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class SettlementOutboxRelayIntegrationTest {

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SettlementCandidateRepository settlementCandidateRepository;

    @Autowired
    private SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 첫 번째 호출만 실패시키는 데 쓴다. 기본은 실제 메서드를 그대로 부른다 */
    @MockitoSpyBean
    private SettlementCreateCandidatesUseCase settlementCreateCandidatesUseCase;

    // 다른 테스트·시드(-1, -2, -3)와 겹치지 않는 주문·주문 항목 ID
    private final long orderId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
    private final long orderItemId1 = orderId;
    private final long orderItemId2 = orderId + 1;
    private final long sellerId = 7_777L;

    @AfterEach
    void cleanUp() {
        List<SettlementCandidate> candidates = findCandidates();
        for (SettlementCandidate candidate : candidates) {
            settlementProcessHistoryRepository.deleteAll(settlementProcessHistoryRepository
                    .findByTargetTypeAndTargetIdOrderByIdAsc(SettlementHistoryTargetType.CANDIDATE, candidate.getId()));
        }
        settlementCandidateRepository.deleteAll(candidates);
        outboxEventRepository.deleteAll(findOutbox());
    }

    @Test
    @DisplayName("구매 확정 이벤트는 outbox에 PENDING으로 저장되고, 릴레이가 전달하면 정산 후보가 생기고 PUBLISHED가 된다")
    void relayCreatesCandidates() {
        publish();

        // 바로 발행하지 않으므로 릴레이 전에는 후보가 없다
        assertThat(findOutbox()).singleElement()
                .satisfies(outbox -> assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING));
        assertThat(findCandidates()).isEmpty();

        outboxRelay.relayPending();

        assertThat(findCandidates()).extracting(SettlementCandidate::getOrderItemId)
                .containsExactlyInAnyOrder(orderItemId1, orderItemId2);
        assertThat(findOutbox()).singleElement()
                .satisfies(outbox -> assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED));
    }

    @Test
    @DisplayName("같은 구매 확정 이벤트가 두 번 전달돼도 주문 항목마다 후보는 하나만 생긴다(멱등)")
    void duplicateEventIsIdempotent() {
        publish();
        publish();

        outboxRelay.relayPending();

        assertThat(findCandidates()).hasSize(2);
        assertThat(findOutbox()).hasSize(2)
                .allSatisfy(outbox -> assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED));
    }

    @Test
    @DisplayName("후보 생성이 실패하면 후보와 PUBLISHED 표시가 함께 롤백되고, 다음 릴레이에서 다시 전달돼 후보가 생긴다")
    void failedListenerIsRetried() {
        willThrow(new IllegalStateException("후보 생성 일시 실패"))
                .willCallRealMethod()
                .given(settlementCreateCandidatesUseCase).createCandidates(eq(orderId), any(), anyList());   // 이 테스트의 주문만 실패
        publish();

        outboxRelay.relayPending();

        assertThat(findCandidates()).isEmpty();
        assertThat(findOutbox()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(outbox.getRetryCount()).isEqualTo(1);
        });

        outboxRelay.relayPending();

        assertThat(findCandidates()).hasSize(2);
        assertThat(findOutbox()).singleElement()
                .satisfies(outbox -> assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED));
    }

    private void publish() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(orderId, 3L, LocalDateTime.now(), ConfirmType.MANUAL,
                List.of(new ConfirmedOrderItemDto(orderItemId1, 1L, sellerId, 10_000L, 1, 10_000L),
                        new ConfirmedOrderItemDto(orderItemId2, 2L, sellerId, 5_000L, 2, 10_000L)));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(event));
    }

    private List<OutboxEvent> findOutbox() {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("ORDER") && outbox.getAggregateId().equals(orderId))
                .toList();
    }

    private List<SettlementCandidate> findCandidates() {
        Set<Long> ids = Set.of(orderItemId1, orderItemId2);
        return settlementCandidateRepository.findAll().stream()
                .filter(candidate -> ids.contains(candidate.getOrderItemId()))
                .toList();
    }
}