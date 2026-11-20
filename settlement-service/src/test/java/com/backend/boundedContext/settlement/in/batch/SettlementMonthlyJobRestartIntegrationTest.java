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
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 월 정산 Job(확정 Step 하나)을 실제 JobRepository(로컬 Docker PostgreSQL의 BATCH_ 테이블)로 실행해,
 * 확정 Step 도중에 멈춘 뒤 같은 정산월로 다시 실행하면 멈춘 판매자부터 이어서 확정하는지 확인한다(정산 목표 2의 자동 검증).
 *
 * 묶음 크기를 1로 줄여 판매자 A, B를 각각 다른 묶음으로 처리하게 하고, 회원 대역이 판매자 B의 상태를 처음 조회할 때 Error를 던진다.
 * Tasklet은 Exception만 잡으므로 Error는 Step 실패가 되고 Job은 FAILED로 끝난다.
 * 회원 서비스 장애(502)도 재시도 끝에 Step 실패가 되고, 같은 달을 다시 실행하면 그 판매자부터 확정한다.
 * Job은 지급하지 않는다(지급은 지급 스케줄러). 그래서 결제 대역은 한 번도 불리지 않아야 한다.
 *
 * 정산월은 실행마다 아직 JobInstance가 없는 과거의 임의 달을 쓴다. 같은 달의 JobInstance가 이미 COMPLETED면 다시 실행할 수 없기 때문이다
 * (Batch 메타 테이블은 지우지 않는다). 테스트 데이터는 판매자 ID를 임의 범위로 잡아 다른 데이터와 섞이지 않게 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"custom.outbox.relayEnabled=false", "custom.settlement.batchChunkSize=1"})
@EmbeddedKafka(topics = {"order.events", "seller.events"}, partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SettlementMonthlyJobRestartIntegrationTest {

    @Autowired private SettlementRunMonthlyJobUseCase runMonthlyJobUseCase;
    @Autowired private SettlementCandidateRepository settlementCandidateRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private SettlementMemberPort settlementMemberPort;
    @MockitoBean private SettlementPaymentPort settlementPaymentPort;

    private final List<Long> candidateIds = new ArrayList<>();
    private final List<Long> executionIds = new ArrayList<>();
    private final AtomicBoolean crashOnSellerB = new AtomicBoolean(true);
    private final AtomicInteger sellerALookups = new AtomicInteger();
    private final AtomicBoolean memberDownForSellerB = new AtomicBoolean(false);
    private final AtomicInteger sellerBLookups = new AtomicInteger();

    private YearMonth targetMonth;
    private long sellerA;
    private long sellerB;

    @BeforeEach
    void setUp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        targetMonth = unusedMonth(random);
        sellerA = 900_000_000L + random.nextLong(1_000_000L) * 10;
        sellerB = sellerA + 1;

        when(settlementMemberPort.getSellers(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            if (ids.contains(sellerA)) sellerALookups.incrementAndGet();
            if (ids.contains(sellerB) && memberDownForSellerB.get()) {
                sellerBLookups.incrementAndGet();
                throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR);   // 회원 서비스 응답 없음(502)
            }
            if (ids.contains(sellerB) && crashOnSellerB.getAndSet(false)) {
                throw new Error("테스트: 확정 도중 프로세스가 죽은 상황");
            }
            return ids.stream().map(this::approved).toList();
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
        jdbcTemplate.update("delete from settlement_process_history where target_type = 'SELLER' and target_id in (?, ?)",
                sellerA, sellerB);
        for (Long id : settlementIds) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = 'SETTLEMENT' and target_id = ?", id);
            jdbcTemplate.update("delete from settlement where id = ?", id);
        }
    }

    @Test
    @DisplayName("확정 Step 도중 멈춘 뒤 같은 달로 다시 실행하면 멈춘 판매자부터 이어서 확정하고, 이미 확정한 판매자는 다시 확정하지 않으며, Job은 지급하지 않는다")
    void restartAfterConfirmCrash_resumesFromCursor() {
        SettlementRunResponse first = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(first.jobExecutionId());

        assertEquals("FAILED", first.status());
        assertEquals(1, settlementCountOf(sellerA));   // A는 첫 묶음에서 확정됨
        assertEquals(0, settlementCountOf(sellerB));   // B는 상태 조회 중 멈춤
        assertEquals("READY", candidateStatusOf(candidateIds.get(2)));

        SettlementRunResponse second = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(second.jobExecutionId());

        assertEquals("COMPLETED", second.status());
        List<String> steps = jobRepository.getJobExecution(second.jobExecutionId()).getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .toList();
        assertEquals(List.of(SettlementJobNames.AGGREGATE_STEP), steps);
        assertEquals(1, settlementCountOf(sellerA));
        assertEquals(1, settlementCountOf(sellerB));
        assertEquals(1, sellerALookups.get());          // A는 재시작에서 다시 확정하지 않는다(커서 다음부터)
        SettlementRow b = settlementOf(sellerB);
        assertEquals(SettlementStatus.READY.name(), b.status());
        assertNotNull(b.payableOn());                   // 지급 예정일이 정해져 지급 스케줄러가 지급한다
        verify(settlementPaymentPort, never()).transfer(any());   // Job은 지급하지 않는다
    }

    @Test
    @DisplayName("회원 서비스 장애로 판매자 상태 조회가 재시도 끝에 실패하면 Job이 FAILED로 멈추고(실패 이력 1건, ERROR 알림), 복구 뒤 같은 달을 다시 실행하면 그 판매자부터 확정한다")
    void memberDown_failsJob_thenRestartConfirms(CapturedOutput output) {
        crashOnSellerB.set(false);
        memberDownForSellerB.set(true);

        SettlementRunResponse first = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(first.jobExecutionId());

        assertEquals("FAILED", first.status());
        assertEquals(3, sellerBLookups.get());          // 처음 + 재시도 2번
        assertEquals(1, settlementCountOf(sellerA));
        assertEquals(0, settlementCountOf(sellerB));
        assertEquals("READY", candidateStatusOf(candidateIds.get(2)));
        List<String> failures = jdbcTemplate.queryForList(
                "select fail_reason from settlement_process_history where batch_execution_id = ? and stage = 'AGGREGATE' and result = 'FAILED'",
                String.class, first.jobExecutionId());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("sellerId=%d..%d".formatted(sellerB, sellerB)));
        assertTrue(output.getOut().contains("월 정산 Job 실패로 멈춤"));

        memberDownForSellerB.set(false);   // 회원 서비스 복구
        SettlementRunResponse second = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(second.jobExecutionId());

        assertEquals("COMPLETED", second.status());
        assertEquals(1, settlementCountOf(sellerA));
        assertEquals(1, settlementCountOf(sellerB));
        assertEquals(1, sellerALookups.get());          // A는 다시 확정하지 않는다
    }

    /**
     * 아직 JobInstance가 없는 과거의 임의 달. Batch 메타 테이블은 지우지 않으므로 이전 테스트 실행이 같은 달을 썼다면
     * 그 달은 COMPLETED로 남아 다시 실행할 수 없다(409-SETTLEMENT_ALREADY_COMPLETED). 그런 달은 건너뛰고 다시 고른다
     */
    private YearMonth unusedMonth(ThreadLocalRandom random) {
        while (true) {
            YearMonth month = YearMonth.of(random.nextInt(1100, 2000), random.nextInt(1, 13));
            JobParameters identifying = new JobParametersBuilder()
                    .addString(SettlementJobNames.PARAM_TARGET_MONTH, month.toString())
                    .toJobParameters();
            if (jobRepository.getJobInstance(SettlementJobNames.JOB_NAME, identifying) == null) return month;
        }
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
                "select id, status, payable_on from settlement where seller_id = ? and settlement_month = ?",
                (rs, n) -> new SettlementRow(rs.getLong("id"), rs.getString("status"), rs.getObject("payable_on")),
                sellerId, targetMonth.toString());
    }

    private record SettlementRow(long id, String status, Object payableOn) {
    }

    private String candidateStatusOf(long candidateId) {
        return jdbcTemplate.queryForObject("select status from settlement_candidate where id = ?", String.class,
                candidateId);
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
