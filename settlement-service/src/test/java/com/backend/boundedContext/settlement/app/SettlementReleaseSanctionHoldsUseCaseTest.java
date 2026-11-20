package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHoldType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판매자 제재 해지: 제재 보류만 READY로 되돌리고 시도 횟수를 초기화한다
 */
@ExtendWith(OutputCaptureExtension.class)
class SettlementReleaseSanctionHoldsUseCaseTest {

    private static final Long SELLER_ID = 7L;

    private final List<Settlement> settlements = new ArrayList<>();
    private final List<SettlementProcessHistory> histories = new ArrayList<>();

    @Test
    @DisplayName("제재 보류만 READY로 되돌리고(시도 횟수 0), 관리자 보류·원장 불일치 보류는 그대로 두며, 해지 사유를 EVENT 이력으로 남긴다")
    void releasesOnlySanctionHolds() {
        Settlement sanctionFailed = held(31L, SettlementHoldType.SANCTION, 2);
        Settlement admin = held(32L, SettlementHoldType.ADMIN, 0);
        Settlement ledger = held(33L, SettlementHoldType.LEDGER_MISMATCH, 0);

        int released = useCase().releaseSanctionHolds(SELLER_ID, "소명 확인", SettlementTrigger.EVENT);

        assertEquals(1, released);
        assertEquals(SettlementStatus.READY, sanctionFailed.getStatus());
        assertEquals(0, sanctionFailed.getRetryCount());
        assertEquals(SettlementStatus.HOLD, admin.getStatus());
        assertEquals(SettlementStatus.HOLD, ledger.getStatus());
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryStage.RELEASE, h.getStage());
        assertEquals(SettlementTrigger.EVENT, h.getTriggerType());
        assertTrue(h.getAdminReason().contains("소명 확인"));
    }

    @Test
    @DisplayName("같은 해지 이벤트를 다시 받아도 이미 READY인 정산서는 건너뛴다(멱등)")
    void idempotent() {
        held(31L, SettlementHoldType.SANCTION, 0);
        SettlementReleaseSanctionHoldsUseCase useCase = useCase();

        assertEquals(1, useCase.releaseSanctionHolds(SELLER_ID, null, SettlementTrigger.EVENT));
        assertEquals(0, useCase.releaseSanctionHolds(SELLER_ID, null, SettlementTrigger.EVENT));
        assertEquals(1, histories.size());
    }

    @Test
    @DisplayName("관리자 보류가 남아 있으면 건수와 정산서 ID를 ERROR 로그로 알리고, 관리자 보류는 그대로 둔다")
    void adminHoldsRemain_logsError(CapturedOutput output) {
        held(31L, SettlementHoldType.SANCTION, 0);
        Settlement admin1 = held(32L, SettlementHoldType.ADMIN, 0);
        Settlement admin2 = held(34L, SettlementHoldType.ADMIN, 0);
        held(33L, SettlementHoldType.LEDGER_MISMATCH, 0);

        int released = useCase().releaseSanctionHolds(SELLER_ID, null, SettlementTrigger.EVENT);

        assertEquals(1, released);
        assertEquals(SettlementStatus.HOLD, admin1.getStatus());
        assertEquals(SettlementStatus.HOLD, admin2.getStatus());
        assertThat(output.getOut()).contains("ERROR").contains("관리자 보류 정산서 2건")
                .contains("sellerId=7").contains("settlementIds=[32, 34]");
    }

    @Test
    @DisplayName("관리자 보류가 없으면 ERROR 알림을 남기지 않는다")
    void noAdminHolds_noError(CapturedOutput output) {
        held(31L, SettlementHoldType.SANCTION, 0);
        held(33L, SettlementHoldType.LEDGER_MISMATCH, 0);

        useCase().releaseSanctionHolds(SELLER_ID, null, SettlementTrigger.EVENT);

        assertThat(output.getOut()).doesNotContain("관리자 보류 정산서");
    }

    private SettlementReleaseSanctionHoldsUseCase useCase() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findWithLockBySellerIdAndHoldType", args -> settlements.stream()
                        .filter(s -> s.getSellerId().equals(args[0]) && s.isHeldBy((SettlementHoldType) args[1]))
                        .toList(),
                "findIdsBySellerIdAndHoldType", args -> settlements.stream()
                        .filter(s -> s.getSellerId().equals(args[0]) && s.isHeldBy((SettlementHoldType) args[1]))
                        .map(Settlement::getId)
                        .toList()));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class, Map.of(
                "save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        return new SettlementReleaseSanctionHoldsUseCase(settlementRepository, historyRepository);
    }

    /** retryCount번 실패한 뒤 이 종류로 보류된 정산서 */
    private Settlement held(Long id, SettlementHoldType type, int retryCount) {
        Settlement s = Settlement.create(SELLER_ID, "2026-10", null);
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "retryCount", retryCount);
        s.hold(type, "보류 사유 " + type);
        settlements.add(s);
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
