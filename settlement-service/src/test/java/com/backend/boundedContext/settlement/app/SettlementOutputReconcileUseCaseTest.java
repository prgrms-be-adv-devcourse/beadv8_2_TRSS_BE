package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementPaymentPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.payment.dto.TransferResultDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 출력 대사: 지급을 시도했지만 PAID가 아닌 정산서에 결제 원장의 지급 기록이 있으면 이력과 로그로만 알린다(정산 데이터는 고치지 않음)
 */
class SettlementOutputReconcileUseCaseTest {

    private static final Long SETTLEMENT_ID = 31L;

    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private Settlement settlement;
    private TransferResultDto ledger;
    private int findTransferCalls;
    private Runnable duringLookup;   // 원장 조회 중 다른 경로가 정산서를 바꾸는 상황

    @Test
    @DisplayName("FAILED 정산서에 원장 지급 기록이 있으면 RECONCILE 이력(MANUAL_REQUIRED)을 남기고 정산서는 그대로 둔다")
    void failedWithLedger_recorded() {
        settlement = settlement(SettlementStatus.FAILED, 1);
        ledger = new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, LocalDateTime.now());

        assertTrue(useCase().reconcile(SETTLEMENT_ID));

        assertEquals(SettlementStatus.FAILED, settlement.getStatus());   // 데이터는 고치지 않는다
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryStage.RECONCILE, h.getStage());
        assertEquals(SettlementHistoryResult.MANUAL_REQUIRED, h.getResult());
        assertEquals("409-SETTLEMENT_LEDGER_PAID_NOT_RECORDED", h.getErrorCode());
    }

    @Test
    @DisplayName("같은 결과가 이미 마지막 이력이면 다시 남기지 않는다")
    void alreadyRecorded_noDuplicateHistory() {
        settlement = settlement(SettlementStatus.HOLD, 1);
        ledger = new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, LocalDateTime.now());
        SettlementOutputReconcileUseCase useCase = useCase();

        assertTrue(useCase.reconcile(SETTLEMENT_ID));
        assertTrue(useCase.reconcile(SETTLEMENT_ID));

        assertEquals(1, histories.size());
    }

    @Test
    @DisplayName("원장에 기록이 없으면 아무것도 남기지 않는다")
    void noLedger_nothing() {
        settlement = settlement(SettlementStatus.MANUAL_REQUIRED, 3);
        ledger = null;

        assertFalse(useCase().reconcile(SETTLEMENT_ID));
        assertTrue(histories.isEmpty());
    }

    @Test
    @DisplayName("지급을 시도한 적이 없거나(retryCount 0) PAID면 결제 원장을 조회하지 않는다")
    void notTarget_skipsLookup() {
        settlement = settlement(SettlementStatus.READY, 0);
        assertFalse(useCase().reconcile(SETTLEMENT_ID));

        settlement = settlement(SettlementStatus.PAID, 1);
        assertFalse(useCase().reconcile(SETTLEMENT_ID));

        assertEquals(0, findTransferCalls);
    }

    @Test
    @DisplayName("원장을 보는 사이 다른 경로가 같은 정산서를 지급했으면(PAID) 경보·이력을 남기지 않는다")
    void paidDuringLookup_noFalseAlarm() {
        settlement = settlement(SettlementStatus.FAILED, 1);
        ledger = new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, LocalDateTime.now());
        duringLookup = () -> settlement = settlement(SettlementStatus.PAID, 1);   // 재시도 스케줄러가 방금 지급함

        assertFalse(useCase().reconcile(SETTLEMENT_ID));
        assertTrue(histories.isEmpty());
    }

    @Test
    @DisplayName("원장을 보는 사이 지급 요청이 시작됐으면(PROCESSING) 경보·이력을 남기지 않는다(지급 결과 재확인이 본다)")
    void processingDuringLookup_noFalseAlarm() {
        settlement = settlement(SettlementStatus.FAILED, 1);
        ledger = new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, LocalDateTime.now());
        duringLookup = () -> settlement = settlement(SettlementStatus.PROCESSING, 1);

        assertFalse(useCase().reconcile(SETTLEMENT_ID));
        assertTrue(histories.isEmpty());
    }

    private SettlementOutputReconcileUseCase useCase() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findById", args -> Optional.ofNullable(settlement)));
        SettlementPaymentPort paymentPort = fake(SettlementPaymentPort.class, Map.of(
                "findTransfer", args -> {
                    findTransferCalls++;
                    if (duringLookup != null) duringLookup.run();
                    return Optional.ofNullable(ledger);
                }));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class, Map.of(
                "findFirstByTargetTypeAndTargetIdAndStageOrderByIdDesc",
                args -> histories.isEmpty() ? Optional.empty() : Optional.of(histories.get(histories.size() - 1)),
                "save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        return new SettlementOutputReconcileUseCase(settlementRepository, paymentPort, historyRepository);
    }

    private static Settlement settlement(SettlementStatus status, int retryCount) {
        Settlement s = Settlement.create(7L, "2026-09", null);
        ReflectionTestUtils.setField(s, "id", SETTLEMENT_ID);
        s.assignIdempotencyKey();
        ReflectionTestUtils.setField(s, "status", status);
        ReflectionTestUtils.setField(s, "retryCount", retryCount);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Function<Object[], Object> handler = handlers.get(method.getName());
            if (handler != null) return handler.apply(args);
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> type.getSimpleName() + "Fake";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
