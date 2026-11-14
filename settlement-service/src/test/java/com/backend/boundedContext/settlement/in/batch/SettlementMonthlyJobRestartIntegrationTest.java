package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.app.SettlementRunMonthlyJobUseCase;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementPaymentPort;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 월 정산 Job을 실제 JobRepository(로컬 Docker PostgreSQL의 BATCH_ 테이블)로 실행해, 지급 Step에서 멈춘 뒤
 * 같은 정산월로 다시 실행하면 지급 Step만 이어서 실행되는지 확인한다(코드리뷰 변경 제안 8, 정산 목표 2의 자동 검증).
 *
 * 회원·결제 Port는 테스트 대역으로 바꾼다. 결제 대역은 두 번째 정산서를 처음 지급할 때 Error를 던진다.
 * SettlementPayoutTasklet은 Exception만 잡으므로 Error는 Step 실패가 되고 Job은 FAILED로 끝난다.
 *
 * 정산월은 실행마다 과거의 임의 달을 쓴다. 같은 달의 JobInstance가 이미 COMPLETED면 다시 실행할 수 없기 때문이다
 * (Batch 메타 테이블은 지우지 않는다). 테스트 데이터는 판매자 ID를 임의 범위로 잡아 다른 데이터와 섞이지 않게 한다.
 */
@SpringBootTest(properties = "custom.outbox.relayEnabled=false")
@EmbeddedKafka(topics = {"order.events", "seller.events"}, partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SettlementMonthlyJobRestartIntegrationTest {

    @Autowired private SettlementRunMonthlyJobUseCase runMonthlyJobUseCase;
    @Autowired private SettlementCandidateRepository settlementCandidateRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private SettlementMemberPort settlementMemberPort;
    @MockitoBean private SettlementPaymentPort settlementPaymentPort;

    private final Map<Long, AtomicInteger> transferCalls = new ConcurrentHashMap<>();
    private final AtomicLong walletTxId = new AtomicLong(700_000L);
    private final List<Long> candidateIds = new ArrayList<>();
    private final List<Long> executionIds = new ArrayList<>();

    private YearMonth targetMonth;
    private long sellerA;
    private long sellerB;
    /** 이 정산서 ID를 처음 지급할 때 Error를 던진다(정산서가 만들어진 뒤 정해진다) */
    private volatile Long crashOnSettlementId;

    @BeforeEach
    void setUp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        targetMonth = YearMonth.of(random.nextInt(1100, 2000), random.nextInt(1, 13));
        sellerA = 900_000_000L + random.nextLong(1_000_000L) * 10;
        sellerB = sellerA + 1;

        when(settlementMemberPort.getSeller(anyLong())).thenAnswer(inv -> approved(inv.getArgument(0)));
        when(settlementMemberPort.getSellers(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(this::approved).toList();
        });
        when(settlementPaymentPort.transfer(any())).thenAnswer(inv -> {
            SettlementTransferCommand command = inv.getArgument(0);
            // 정산서 ID는 확정 Step에서 정해지므로 첫 지급 호출 때 판매자 B의 정산서를 멈출 대상으로 정한다
            if (crashOnSettlementId == null) crashOnSettlementId = settlementOf(sellerB).id();
            int call = transferCalls.computeIfAbsent(command.settlementId(), id -> new AtomicInteger()).incrementAndGet();
            if (command.settlementId().equals(crashOnSettlementId) && call == 1) {
                throw new Error("테스트: 지급 도중 프로세스가 죽은 상황");
            }
            return new TransferResultDto(command.idempotencyKey(), walletTxId.incrementAndGet(),
                    command.payoutAmount(), command.feeAmount(), LocalDateTime.now());
        });

        LocalDateTime confirmedAt = targetMonth.atDay(15).atTime(12, 0);
        long orderItemBase = sellerA * 10;
        candidateIds.add(saveCandidate(orderItemBase + 1, sellerA, 20_000L, confirmedAt));
        candidateIds.add(saveCandidate(orderItemBase + 2, sellerA, 10_000L, confirmedAt));
        candidateIds.add(saveCandidate(orderItemBase + 3, sellerB, 30_000L, confirmedAt));
    }

    @AfterEach
    void cleanUp() {
        // 원장 무결성 1단계(@PreRemove)는 JPA 삭제만 막으므로 테스트 정리는 SQL로 한다
        List<Long> settlementIds = settlementIds();
        for (Long id : executionIds) {
            jdbcTemplate.update("delete from settlement_process_history where batch_execution_id = ?", id);
        }
        for (Long id : candidateIds) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = 'CANDIDATE' and target_id = ?", id);
            jdbcTemplate.update("delete from settlement_candidate where id = ?", id);
        }
        for (Long id : settlementIds) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = 'SETTLEMENT' and target_id = ?", id);
            jdbcTemplate.update("delete from settlement where id = ?", id);
        }
    }

    @Test
    @DisplayName("지급 Step에서 멈춘 뒤 같은 달로 다시 실행하면 지급 Step만 이어서 실행되고, 이미 지급한 정산서는 다시 지급하지 않는다")
    void restartAfterPayoutCrash_resumesOnlyPayoutStep() {
        SettlementRunResponse first = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(first.jobExecutionId());

        assertEquals("FAILED", first.status());
        SettlementRow settlementA = settlementOf(sellerA);
        SettlementRow settlementB = settlementOf(sellerB);
        // 판매자 A가 B보다 먼저 지급되어야 시나리오가 성립한다(판매자 ID 순으로 확정하므로 정산서 ID도 A가 앞선다)
        assertTrue(settlementA.id() < settlementB.id());
        assertEquals(SettlementStatus.PAID.name(), settlementA.status());
        assertEquals(SettlementStatus.READY.name(), settlementB.status());   // Error로 지급 트랜잭션이 롤백됐다

        SettlementRunResponse second = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(second.jobExecutionId());

        assertEquals("COMPLETED", second.status());
        List<String> steps = jobRepository.getJobExecution(second.jobExecutionId()).getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .toList();
        assertEquals(List.of(SettlementJobNames.PAYOUT_STEP), steps);
        assertEquals(1, settlementCountOf(sellerA));
        assertEquals(1, settlementCountOf(sellerB));
        assertEquals(1, transferCalls.get(settlementA.id()).get());   // A는 다시 지급하지 않는다
        assertEquals(2, transferCalls.get(settlementB.id()).get());   // B는 멈춘 지급 1번 + 이어서 1번
        assertEquals(SettlementStatus.PAID.name(), settlementOf(sellerB).status());
    }

    private SellerDto approved(Long sellerId) {
        return new SellerDto(sellerId, sellerId + 1_000_000_000L, "테스트상점-" + sellerId, SellerStatus.APPROVED);
    }

    private long saveCandidate(long orderItemId, long sellerId, long salePrice, LocalDateTime confirmedAt) {
        SettlementCandidate candidate = SettlementCandidate.create(orderItemId, orderItemId, sellerId, salePrice, 10,
                confirmedAt);
        return settlementCandidateRepository.save(candidate).getId();
    }

    /** 정산서 조회는 잠금 조회만 있어 테스트에서는 SQL로 읽는다 */
    private SettlementRow settlementOf(long sellerId) {
        return jdbcTemplate.queryForObject(
                "select id, status from settlement where seller_id = ? and settlement_month = ?",
                (rs, n) -> new SettlementRow(rs.getLong("id"), rs.getString("status")), sellerId,
                targetMonth.toString());
    }

    private record SettlementRow(long id, String status) {
    }

    private int settlementCountOf(long sellerId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from settlement where seller_id = ? and settlement_month = ?", Integer.class,
                sellerId, targetMonth.toString());
        return count == null ? 0 : count;
    }

    private List<Long> settlementIds() {
        return jdbcTemplate.queryForList("select id from settlement where seller_id in (?, ?) and settlement_month = ?",
                Long.class, sellerA, sellerB, targetMonth.toString());
    }
}
