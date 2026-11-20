package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementHoldType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매자 제재 해지 → 그 판매자의 제재 보류(SANCTION) 정산서를 READY로 되돌린다
 *
 * 회원 서비스의 판매자 제재 해지 이벤트(SellerUnsanctionedEvent)를 받아 실행한다.
 * 제재 보류만 푼다. 관리자 보류(ADMIN)와 원장 불일치 보류(LEDGER_MISMATCH)는 각자의 해제 경로가 있어 그대로 둔다.
 * 되돌릴 때 시도 횟수를 0으로 초기화한다(정책 확정). 제재 전 실패는 제재와 상관없는 지난 시도라, 해지 뒤 자동 지급을 처음(3회)부터 다시 한다.
 * READY가 되면 재시도 스케줄러가 지급한다. 지급 직전 확인(판매자 상태, 원장 불일치)은 그대로 다시 한다.
 * 정산 후보는 제재 때 보류하지 않으므로(정산서만 보류) 되돌릴 것이 없다. 후보는 다음 월 정산에서 그 시점의 판매자 상태로 확정된다.
 * 이미 READY인 정산서는 조건에서 빠지므로 같은 이벤트를 다시 받아도 결과가 같다(멱등).
 * 그 판매자에게 관리자 보류가 남아 있으면 운영자가 직접 해제하도록 ERROR 로그(Slack 알림)를 남긴다.
 *
 * 알려진 한계: 해지 이벤트를 처리하는 순간 HOLD인 정산서만 푼다. 판매자 상태를 잠금 없이 미리 읽고 SANCTION 보류를 거는 두 곳
 * (SettlementAggregateUseCase 확정 단계, SettlementPayoutUseCase 지급 직전 확인)에서 "SANCTIONED로 읽음 → 해지 이벤트 처리(0건)
 * → 보류 커밋" 순서가 되면 그 보류는 자동으로 풀리지 않는다. 틈이 짧아 드물고, 돈이 잘못 나가지 않고 HOLD로 멈출 뿐이며
 * 관리자 보류 해제(SettlementReleaseUseCase)로 풀 수 있어 지금은 자동 정리를 두지 않는다.
 * 해지된 판매자의 SANCTION 보류가 남는 일이 실제로 자주 일어나면 원장 불일치 보류 정리(SettlementScheduler의 원장 재대조 스케줄,
 * findSellerIdsWithLedgerMismatchHolds → releaseLedgerMismatchHoldsIfResolved)와 같은 구조로 정리 스케줄러를 추가한다.
 *   1) settlementRepository.findSellerIdsByHoldType(SANCTION)으로 SANCTION 보류가 남은 판매자 ID를 가져온다
 *   2) settlementMemberPort.getSellers(ids)로 회원 서비스에 한 번에 상태를 묻는다
 *   3) 조회됐고 SANCTIONED가 아니면 이 클래스의 releaseSanctionHolds로 푼다(시도 횟수 초기화, RELEASE 이력).
 *      아직 SANCTIONED이거나 조회되지 않으면 그대로 둔다(안전한 쪽)
 *   4) 판매자 하나가 실패해도 나머지는 계속하고 실패는 ERROR 로그로 남긴다
 *   주기는 매일 1회(원장 정리 직후)면 충분하고, 이력 trigger는 재시도와 구분되게 SettlementTrigger에 새 값을 추가한다.
 *   푼 뒤 판매자가 다시 제재되더라도 지급 직전 확인이 판매자 상태를 새로 읽어 다시 보류하므로 잘못 지급되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementReleaseSanctionHoldsUseCase {

    private final SettlementRepository settlementRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    /** @return 되돌린 정산서 수 */
    public int releaseSanctionHolds(Long sellerId, String reason, SettlementTrigger trigger) {
        LocalDateTime now = LocalDateTime.now();
        List<Settlement> held = settlementRepository.findWithLockBySellerIdAndHoldType(sellerId,
                SettlementHoldType.SANCTION);
        String historyReason = reason == null || reason.isBlank() ? "판매자 제재 해지" : "판매자 제재 해지: " + reason;
        for (Settlement settlement : held) {
            settlement.releaseSanctionHold();
            settlementProcessHistoryRepository.save(
                    SettlementProcessHistory.released(settlement.getId(), trigger, historyReason, now));
        }
        log.info("판매자 제재 해지로 정산 보류 해제 sellerId={} trigger={} released={}", sellerId, trigger, held.size());

        // 관리자 보류는 제재 해지로 풀지 않는다. #225 전 holdSeller로 건 제재 보류도 관리자 보류로 남아 있어(V3 마이그레이션)
        // 운영자가 확인해 직접 해제하도록 ERROR로 알린다(제재와 상관없는 관리자 보류도 함께 알린다)
        List<Long> adminHeldIds = settlementRepository.findIdsBySellerIdAndHoldType(sellerId, SettlementHoldType.ADMIN);
        if (!adminHeldIds.isEmpty()) {
            log.error("판매자 제재가 해지됐지만 관리자 보류 정산서 {}건이 남아 있습니다 sellerId={} settlementIds={}. "
                    + "제재 때문에 건 보류라면 관리자 정산 화면에서 해제해 주세요", adminHeldIds.size(), sellerId, adminHeldIds);
        }
        return held.size();
    }
}
