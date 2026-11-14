package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementPaymentPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.payment.dto.TransferResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 출력 대사: 결제 원장에는 지급 기록이 있는데 정산서가 PAID가 아닌 경우를 찾는다
 *
 * 원장 재대조(VERIFY)는 PAID 정산서만 보므로 "지급은 됐는데 정산서는 FAILED·HOLD·MANUAL_REQUIRED"인 상태를 찾지 못한다.
 * (예: 결제가 지급을 커밋한 뒤 응답이 늦어 정산서가 실패로 기록되고, 그 뒤 제재로 HOLD가 됨)
 * 이 상태는 미정산 금액을 실제보다 크게 만들어 판매자 철회·탈퇴를 막는다.
 *
 * 대상: 지급을 한 번 이상 시도했지만(retryCount > 0) PAID가 아닌 READY·FAILED·MANUAL_REQUIRED·HOLD 정산서
 * 방법: 같은 멱등 키로 결제 원장을 조회(findTransfer). 기록이 있으면 정산 데이터는 고치지 않고
 *      처리 이력(stage RECONCILE, MANUAL_REQUIRED, 409-SETTLEMENT_LEDGER_PAID_NOT_RECORDED)과 ERROR 로그로 사람에게 알린다.
 *      같은 결과가 이미 마지막 이력이면 다시 남기지 않는다.
 * 사람이 할 일: READY·FAILED는 다음 지급이 같은 키로 기존 결과를 받아 PAID가 된다. MANUAL_REQUIRED는 관리자 재처리로 같은 결과를 받는다.
 *      HOLD는 보류 사유를 확인하고 해제하면 같은 방식으로 PAID가 된다(지급이 두 번 일어나지 않는다).
 * 원장 불일치 보류(LedgerMismatchRule)에는 쓰지 않는다. 그 판매자의 지급을 막으면 오히려 PAID로 수렴하지 못한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementOutputReconcileUseCase {

    /** 출력 대사 대상 상태 (지급 요청 결과를 기다리는 정산서는 지급 결과 재확인이 본다) */
    static final List<SettlementStatus> TARGET_STATUSES = List.of(SettlementStatus.READY, SettlementStatus.FAILED,
            SettlementStatus.MANUAL_REQUIRED, SettlementStatus.HOLD);

    private final SettlementRepository settlementRepository;
    private final SettlementPaymentPort settlementPaymentPort;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    public List<Long> findTargetIds(Long afterSettlementId, int limit) {
        return settlementRepository.findOutputReconcileTargetIds(TARGET_STATUSES, afterSettlementId,
                PageRequest.of(0, limit));
    }

    /**
     * 정산서 1건 출력 대사. 결제 원장에 지급 기록이 있으면 true(사람 확인 필요)
     * 결제 조회가 예외로 실패하면 그대로 던진다(호출한 쪽이 오류 건수로 세고 다음 회차에 다시 본다)
     */
    public boolean reconcile(Long settlementId) {
        Optional<Settlement> found = settlementRepository.findById(settlementId);
        if (found.isEmpty()) return false;
        Settlement settlement = found.get();
        if (!TARGET_STATUSES.contains(settlement.getStatus()) || settlement.getRetryCount() == 0) return false;

        Optional<TransferResultDto> ledger = settlementPaymentPort.findTransfer(settlement.getIdempotencyKey());
        if (ledger.isEmpty()) return false;

        String code = SettlementErrorCode.SETTLEMENT_LEDGER_PAID_NOT_RECORDED.resultCode();
        boolean alreadyRecorded = settlementProcessHistoryRepository
                .findFirstByTargetTypeAndTargetIdAndStageOrderByIdDesc(SettlementHistoryTargetType.SETTLEMENT,
                        settlementId, SettlementHistoryStage.RECONCILE)
                .filter(last -> Objects.equals(last.getErrorCode(), code))
                .isPresent();
        if (!alreadyRecorded) {
            settlementProcessHistoryRepository.save(SettlementProcessHistory.paidNotRecorded(settlementId,
                    settlement.getStatus(), ledger.get().walletTransactionId(), LocalDateTime.now()));
        }
        // 운영자가 알아야 하는 상태라 ERROR로 남겨 기존 알림으로 받는다(이미 기록한 건도 해결될 때까지 매일 알린다)
        log.error("정산 출력 대사: 결제 원장에 지급 기록이 있는데 정산서가 PAID가 아님 settlementId={} sellerId={} status={} "
                        + "walletTxId={} 새 이력={}", settlementId, settlement.getSellerId(), settlement.getStatus(),
                ledger.get().walletTransactionId(), !alreadyRecorded);
        return true;
    }
}
