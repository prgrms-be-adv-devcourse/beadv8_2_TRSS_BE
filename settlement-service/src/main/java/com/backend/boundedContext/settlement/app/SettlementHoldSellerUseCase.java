package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매자 제재 시 정산 보류
 * - 판매자 제재 이벤트(SellerSanctionedEvent)를 받은 정산 컨슈머가 호출한다(trigger EVENT)
 * - #225 전까지는 회원 컨텍스트도 SettlementApi.holdSeller로 직접 호출한다(trigger ADMIN)
 *
 * 그 판매자의 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 모두 HOLD로 바꾸고 보류 사유를 남긴다.
 * 정산 후보 상태는 바꾸지 않는다(READY 후보는 다음 월 정산에서 집계될 때 정산서가 HOLD로 만들어진다
 * → SettlementAggregateUseCase가 판매자 상태 SANCTIONED를 보고 처리).
 * 호출한 쪽 트랜잭션에 참여한다(컨슈머: 처리 기록과 함께 커밋·롤백, SettlementApi: 회원 제재와 함께 커밋·롤백).
 * 이미 HOLD·PAID인 정산서는 조건에서 빠지므로 같은 이벤트를 다시 받아도 결과가 같다(멱등).
 * 지급 중인 정산서는 잠금을 기다렸다가, PAID가 되면 조건에서 빠지므로 보류하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementHoldSellerUseCase {

    /** 보류할 수 있는 지급 전 상태 */
    private static final List<SettlementStatus> PAYABLE_STATUSES =
            List.of(SettlementStatus.READY, SettlementStatus.FAILED, SettlementStatus.MANUAL_REQUIRED);

    private final SettlementRepository settlementRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    /** @return 보류한 정산서 수 */
    public int holdSeller(Long sellerId, String reason, SettlementTrigger trigger) {
        LocalDateTime now = LocalDateTime.now();
        List<Settlement> settlements = settlementRepository.findWithLockBySellerIdAndStatusIn(sellerId,
                PAYABLE_STATUSES);
        for (Settlement settlement : settlements) {
            settlement.hold(reason);
            settlementProcessHistoryRepository.save(SettlementProcessHistory.held(settlement.getId(), reason, trigger, now));
        }
        log.info("판매자 제재로 정산 보류 sellerId={} trigger={} held={}", sellerId, trigger, settlements.size());
        return settlements.size();
    }
}