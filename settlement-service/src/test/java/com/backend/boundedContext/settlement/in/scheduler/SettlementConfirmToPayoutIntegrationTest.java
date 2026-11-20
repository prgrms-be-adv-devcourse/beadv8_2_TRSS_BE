package com.backend.boundedContext.settlement.in.scheduler;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.app.SettlementRunMonthlyJobUseCase;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
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
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정산 흐름을 실제 PostgreSQL·Spring Batch로 이어서 확인한다: 사전 판별(이력만) → 확정(월 정산 Job) → 지급(지급 스케줄러)
 * 회원·결제 Port만 테스트 대역으로 바꾼다. 결제 대역은 받은 지급 요청 금액 그대로 지급 결과를 돌려준다.
 *
 * 테스트 데이터는 판매자 ID를 임의 범위로 잡아 다른 데이터와 섞이지 않게 하고, 끝나면 SQL로 지운다.
 */
@SpringBootTest(properties = "custom.outbox.relayEnabled=false")
@EmbeddedKafka(topics = {"order.events", "seller.events"}, partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SettlementConfirmToPayoutIntegrationTest {

    @Autowired private SettlementRunMonthlyJobUseCase runMonthlyJobUseCase;
    @Autowired private SettlementScheduler settlementScheduler;
    @Autowired private SettlementCandidateRepository settlementCandidateRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private SettlementMemberPort settlementMemberPort;
    @MockitoBean private SettlementPaymentPort settlementPaymentPort;

    private final List<Long> candidateIds = new ArrayList<>();
    private final List<Long> executionIds = new ArrayList<>();
    private final AtomicLong walletTxId = new AtomicLong(800_000L);
    private long sellerId;

    @BeforeEach
    void setUp() {
        sellerId = 910_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L) * 10;
        when(settlementMemberPort.getSeller(anyLong())).thenAnswer(inv -> approved(inv.getArgument(0)));
        when(settlementMemberPort.getSellers(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(this::approved).toList();
        });
        when(settlementPaymentPort.transfer(any())).thenAnswer(inv -> {
            SettlementTransferCommand command = inv.getArgument(0);
            return new TransferResultDto(command.idempotencyKey(), walletTxId.incrementAndGet(),
                    command.payoutAmount(), command.feeAmount(), LocalDateTime.now());
        });
    }

    @AfterEach
    void cleanUp() {
        // 원장 무결성 1단계(@PreRemove)는 JPA 삭제만 막으므로 테스트 정리는 SQL로 한다
        List<Long> settlementIds = jdbcTemplate.queryForList("select id from settlement where seller_id = ?",
                Long.class, sellerId);
        for (Long id : executionIds) {
            jdbcTemplate.update("delete from settlement_process_history where batch_execution_id = ?", id);
        }
        for (Long id : candidateIds) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = 'CANDIDATE' and target_id = ?", id);
            jdbcTemplate.update("delete from settlement_candidate where id = ?", id);
        }
        jdbcTemplate.update("delete from settlement_process_history where target_type = 'SELLER' and target_id = ?", sellerId);
        for (Long id : settlementIds) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = 'SETTLEMENT' and target_id = ?", id);
            jdbcTemplate.update("delete from settlement where id = ?", id);
        }
    }

    @Test
    @DisplayName("확정(월 정산 Job)이 지급 예정일을 정한 READY 정산서를 만들고, 지급 예정일이 되면 지급 스케줄러가 결제에 지급을 요청해 PAID로 확정한다")
    void confirmThenPayoutScheduler_paid() {
        // V5(지급 예정일, 원본 이벤트 ID)가 적용돼 있다(이 버전이 마지막인지는 보지 않는다)
        assertTrue(jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '5'", Boolean.class));
        YearMonth targetMonth = unusedMonth();
        LocalDateTime confirmedAt = targetMonth.atDay(15).atTime(12, 0);
        candidateIds.add(saveCandidate(sellerId * 10 + 1, 20_000L, confirmedAt));
        candidateIds.add(saveCandidate(sellerId * 10 + 2, 10_000L, confirmedAt));

        SettlementRunResponse run = runMonthlyJobUseCase.run(targetMonth, SettlementTrigger.ADMIN);
        executionIds.add(run.jobExecutionId());

        assertEquals("COMPLETED", run.status());
        Map<String, Object> confirmed = settlementOf(targetMonth);
        assertEquals("READY", confirmed.get("status"));
        assertEquals(27_000L, ((Number) confirmed.get("payout_amount")).longValue());   // 30,000 - 수수료 10%
        LocalDate payableOn = ((Date) confirmed.get("payable_on")).toLocalDate();
        assertEquals(SettlementPolicy.payableDateOf(LocalDate.now()), payableOn);       // 확정한 날(주말이면 월요일)
        verify(settlementPaymentPort, never()).transfer(any());                         // Job은 지급하지 않는다

        if (payableOn.isAfter(LocalDate.now())) {
            // 주말에 확정했으면 아직 지급 예정일 전이라 지급하지 않는다. 지급 예정일이 됐다고 보고 이어서 확인한다
            settlementScheduler.retryPayouts();
            assertEquals("READY", settlementOf(targetMonth).get("status"));
            jdbcTemplate.update("update settlement set payable_on = ? where id = ?", Date.valueOf(LocalDate.now()),
                    ((Number) confirmed.get("id")).longValue());
        }

        settlementScheduler.retryPayouts();

        Map<String, Object> paid = settlementOf(targetMonth);
        long settlementId = ((Number) paid.get("id")).longValue();
        assertEquals("PAID", paid.get("status"));
        assertEquals(27_000L, ((Number) paid.get("payout_amount")).longValue());
        verify(settlementPaymentPort).transfer(argThat(command -> command.settlementId().equals(settlementId)
                && command.payoutAmount() == 27_000L && command.feeAmount() == 3_000L));
    }

    @Test
    @DisplayName("사전 판별은 지난달 정산 대상 판매자를 판별해 이력(PREJUDGE)만 남기고, 후보는 READY 그대로이며 정산서를 만들지 않는다")
    void prejudge_recordsHistoryOnly() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        long candidateId = saveCandidate(sellerId * 10 + 1, 15_000L, lastMonth.atDay(10).atTime(9, 0));
        candidateIds.add(candidateId);

        settlementScheduler.prejudge();

        List<String> results = jdbcTemplate.queryForList("""
                select result from settlement_process_history
                 where target_type = 'SELLER' and target_id = ? and stage = 'JUDGE' and trigger_type = 'PREJUDGE'
                """, String.class, sellerId);
        assertEquals(List.of("SUCCEEDED"), results);   // 승인된 판매자, 금액 정상 → PASS
        assertEquals("READY", jdbcTemplate.queryForObject("select status from settlement_candidate where id = ?",
                String.class, candidateId));
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from settlement where seller_id = ?",
                Integer.class, sellerId));
    }

    /** 아직 JobInstance가 없는 과거의 임의 달(같은 달이 이미 COMPLETED면 다시 실행할 수 없으므로) */
    private YearMonth unusedMonth() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (true) {
            YearMonth month = YearMonth.of(random.nextInt(1100, 2000), random.nextInt(1, 13));
            JobParameters identifying = new JobParametersBuilder()
                    .addString(SettlementJobNames.PARAM_TARGET_MONTH, month.toString())
                    .toJobParameters();
            if (jobRepository.getJobInstance(SettlementJobNames.JOB_NAME, identifying) == null) return month;
        }
    }

    private SellerDto approved(Long id) {
        return new SellerDto(id, id + 1_000_000_000L, "테스트상점-" + id, SellerStatus.APPROVED);
    }

    private long saveCandidate(long orderItemId, long salePrice, LocalDateTime confirmedAt) {
        return settlementCandidateRepository.save(SettlementCandidate.create(orderItemId, orderItemId, sellerId,
                salePrice, 10, confirmedAt)).getId();
    }

    /** 정산서 조회는 잠금 조회만 있어 테스트에서는 SQL로 읽는다 */
    private Map<String, Object> settlementOf(YearMonth month) {
        return jdbcTemplate.queryForMap(
                "select id, status, payout_amount, payable_on from settlement where seller_id = ? and settlement_month = ?",
                sellerId, month.toString());
    }
}
