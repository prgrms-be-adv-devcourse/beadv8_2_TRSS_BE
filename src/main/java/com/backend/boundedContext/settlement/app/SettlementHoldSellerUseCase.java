package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매자 제재 시 정산 보류 (회원 컨텍스트가 SettlementApi.holdSeller로 호출)
 *
 * 그 판매자의 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 모두 HOLD로 바꾸고 보류 사유를 남긴다.
 * 정산 후보 상태는 바꾸지 않는다(READY 후보는 다음 월 정산에서 집계될 때 정산서가 HOLD로 만들어진다
 * → SettlementAggregateUseCase가 판매자 상태 SANCTIONED를 보고 처리).
 * 회원 컨텍스트 트랜잭션(제재)에 참여하므로 제재가 롤백되면 보류도 함께 롤백된다.
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
    public int holdSeller(Long sellerId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        List<Settlement> settlements = settlementRepository.findWithLockBySellerIdAndStatusIn(sellerId,
                PAYABLE_STATUSES);
        for (Settlement settlement : settlements) {
            settlement.hold(reason);
            settlementProcessHistoryRepository.save(SettlementProcessHistory.held(settlement.getId(), reason, now));
        }
        log.info("판매자 제재로 정산 보류 sellerId={} held={}", sellerId, settlements.size());
        return settlements.size();
    }
}