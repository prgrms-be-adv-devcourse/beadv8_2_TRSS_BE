package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementLedgerCheckResult;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
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
 * 원장 무결성 2단계 ②: 지급 영수증 재대조
 *
 * 지급된 정산서의 영수증(원장 행 ID·지급액·수수료)과 결제 원장을 다시 읽은 값(findTransfer)이 같은지 본다.
 * 다르면 정산 데이터는 고치지 않고 처리 이력에 MANUAL_REQUIRED로 남긴다.
 * 판별 단계(LedgerMismatchRule)가 이 이력을 보고 그 판매자의 다음 지급을 보류한다.
 *
 * 언제: 매일 03:35 스케줄러가 지난 lookbackDays(2)일 동안 지급된 정산서(T+1)와,
 *       지급일과 상관없이 불일치가 아직 풀리지 않은 정산서를 본다. 월 정산 Job 안에서는 하지 않는다.
 *       (풀리지 않은 정산서를 계속 다시 봐야 원장이 고쳐졌을 때 불일치가 풀린다)
 * 지급 직후 비교는 지급 트랜잭션의 matchesTransfer가 한다.
 *
 * 이력: 직전 재대조 결과와 같으면 다시 남기지 않는다(풀리지 않은 불일치를 매일 다시 봐도 이력이 쌓이지 않음).
 * 자동 해제: 불일치가 풀리고(MANUAL_REQUIRED → OK) 그 판매자에게 다른 불일치가 남지 않았으면,
 *          원장 불일치 사유(LedgerMismatchRule.REASON)로 보류된 그 판매자의 정산서를 READY로 되돌린다.
 *          관리자 보류·판매자 제재 보류는 사유가 달라 건드리지 않는다. READY가 되면 재시도 스케줄러가 지급한다.
 * 정리: 해소되는 순간에만 풀면 그 뒤에 생긴 원장 불일치 보류(재시작한 Job이 예전 판정을 적용, 해소와 동시에 커밋된 지급 직전 보류)를
 *      놓친다. 그래서 매일 재대조 끝에 "불일치가 없는데 원장 불일치 사유로 보류된" 정산서를 한 번 더 찾아 푼다
 *      (findSellerIdsWithLedgerMismatchHolds → releaseLedgerMismatchHoldsIfResolved).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementLedgerRecheckUseCase {

    private final SettlementRepository settlementRepository;
    private final SettlementPaymentPort settlementPaymentPort;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    public List<Long> findTargetIds(LocalDateTime since, Long afterSettlementId, int limit) {
        return settlementRepository.findLedgerRecheckTargetIds(since, afterSettlementId, PageRequest.of(0, limit));
    }

    /** 원장 불일치 사유로 보류된 정산서가 있는 판매자 ID (매일 정리 대상) */
    public List<Long> findSellerIdsWithLedgerMismatchHolds() {
        return settlementRepository.findSellerIdsByHoldReason(LedgerMismatchRule.REASON);
    }

    /**
     * 정리: 판매자에게 풀리지 않은 불일치가 없으면 원장 불일치 사유로 보류된 정산서를 READY로 되돌린다
     * @return 해제한 정산서 수 (불일치가 남아 있으면 0)
     */
    public int releaseLedgerMismatchHoldsIfResolved(Long sellerId, SettlementTrigger trigger) {
        if (settlementRepository.existsUnresolvedLedgerMismatchBySellerId(sellerId)) return 0;
        return releaseLedgerMismatchHolds(sellerId, trigger, "원장 재대조 불일치가 없음을 확인해 자동 해제",
                LocalDateTime.now());
    }

    /** 지급일과 상관없이 불일치가 아직 풀리지 않은 정산서 ID (매일 다시 본다) */
    public List<Long> findUnresolvedTargetIds() {
        return settlementProcessHistoryRepository.findUnresolvedLedgerMismatchSettlementIds();
    }

    /**
     * 정산서 1건 재대조. PAID가 아니면(그사이 상태가 바뀜) 비교하지 않고 OK로 본다
     * 결제 조회가 예외로 실패하면 그대로 던진다(호출한 쪽이 실패 건수로 세고 다음 회차에 다시 본다)
     */
    public SettlementLedgerCheckResult recheck(Long settlementId, SettlementTrigger trigger, Long batchExecutionId) {
        Optional<Settlement> found = settlementRepository.findById(settlementId);
        if (found.isEmpty() || found.get().getStatus() != SettlementStatus.PAID) return SettlementLedgerCheckResult.OK;
        Settlement settlement = found.get();

        TransferResultDto ledger = settlementPaymentPort.findTransfer(settlement.getIdempotencyKey()).orElse(null);
        SettlementLedgerCheckResult result = settlement.checkLedger(ledger);

        Optional<SettlementProcessHistory> last = settlementProcessHistoryRepository
                .findFirstByTargetTypeAndTargetIdAndStageOrderByIdDesc(SettlementHistoryTargetType.SETTLEMENT,
                        settlementId, SettlementHistoryStage.VERIFY);
        // 직전 결과와 같으면(OK → OK, 같은 불일치 → 같은 불일치) 이력을 다시 남기지 않는다
        if (last.isPresent() && Objects.equals(last.get().getErrorCode(), result.resultCode())) return result;

        LocalDateTime now = LocalDateTime.now();
        settlementProcessHistoryRepository.save(SettlementProcessHistory.ledgerChecked(settlementId, result, trigger,
                batchExecutionId, now));
        if (result.isMismatch()) {
            log.warn("정산 원장 재대조 불일치 settlementId={} sellerId={} result={}", settlementId,
                    settlement.getSellerId(), result);
            return result;
        }
        boolean resolved = last.isPresent() && last.get().getResult() == SettlementHistoryResult.MANUAL_REQUIRED;
        if (!resolved) return result;
        // 방금 저장한 OK 이력은 같은 트랜잭션의 조회 전에 반영(flush)되므로 이 정산서는 미해결에서 빠진 상태로 센다
        Long sellerId = settlement.getSellerId();
        if (settlementRepository.existsUnresolvedLedgerMismatchBySellerId(sellerId)) {
            log.info("정산 원장 재대조 불일치 해소 settlementId={} sellerId={} (다른 불일치가 남아 보류 유지)",
                    settlementId, sellerId);
            return result;
        }
        int released = releaseLedgerMismatchHolds(sellerId, trigger,
                "원장 재대조 불일치 해소(settlementId=%d)로 자동 해제".formatted(settlementId), now);
        log.info("정산 원장 재대조 불일치 해소 settlementId={} sellerId={} 자동 해제 {}건", settlementId, sellerId,
                released);
        return result;
    }

    /** 원장 불일치 사유로 보류된 이 판매자의 정산서를 잠가서 READY로 되돌린다. 관리자 보류·제재 보류는 그대로 */
    private int releaseLedgerMismatchHolds(Long sellerId, SettlementTrigger trigger, String reason,
                                           LocalDateTime now) {
        int released = 0;
        for (Settlement held : settlementRepository.findWithLockBySellerIdAndStatusIn(sellerId,
                List.of(SettlementStatus.HOLD))) {
            if (!LedgerMismatchRule.REASON.equals(held.getHoldReason())) continue;   // 관리자 보류·제재 보류는 그대로
            held.release();
            settlementProcessHistoryRepository.save(
                    SettlementProcessHistory.released(held.getId(), trigger, reason, now));
            released++;
        }
        return released;
    }
}
