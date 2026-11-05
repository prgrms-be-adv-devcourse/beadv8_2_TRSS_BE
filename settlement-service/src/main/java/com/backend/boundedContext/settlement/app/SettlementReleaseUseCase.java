package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 관리자 보류 해제: HOLD 정산서를 READY로 되돌리고 처리 이력을 남긴다
 *
 * 지급은 여기서 하지 않는다. READY가 되면 10분마다 도는 재시도 스케줄러가 지급한다(정책 "보류").
 * 판매자가 제재(SANCTIONED) 중이면 409 SETTLEMENT_SELLER_SANCTIONED로 거절한다.
 * 지급 직전에 판매자가 제재 상태면 다시 HOLD가 되므로(SettlementPayoutUseCase), 해제해도 지급되지 않고 보류만 되풀이된다.
 * 제재 중인 판매자의 정산서는 판매자 제재가 해지될 때 함께 READY로 바꾼다(제재 해지 작업에서 추가 예정).
 * HOLD가 아니면 엔티티가 409 SETTLEMENT_STATE_INVALID를 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementReleaseUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final SettlementMemberPort settlementMemberPort;

    public SettlementResponse release(Long settlementId) {
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        if (settlement.getStatus() == SettlementStatus.HOLD
                && settlementMemberPort.getSeller(settlement.getSellerId()).status() == SellerStatus.SANCTIONED) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_SELLER_SANCTIONED);
        }
        settlement.release();   // HOLD가 아니면 409
        settlementProcessHistoryRepository.save(SettlementProcessHistory.released(settlementId, LocalDateTime.now()));
        log.info("정산서 보류 해제 settlementId={} sellerId={}", settlementId, settlement.getSellerId());
        return SettlementResponse.of(settlement, settlementSupport.findBusinessName(settlement.getSellerId()));
    }
}