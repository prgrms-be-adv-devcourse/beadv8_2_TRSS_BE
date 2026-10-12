package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementCreateCandidatesUseCase {

    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    /**
     * 구매 확정된 주문 항목마다 정산 후보(READY) 만들기
     *   - 같은 주문 항목이 다시 오면 건너뛴다(order_item_id로 먼저 확인, DB 유니크는 동시 요청 안전망)
     *   - 금액 이상(단가 × 수량 불일치, 0원 이하)이면 후보를 만들지 않고 처리 이력에 실패를 남긴다
     *   - 구매 확정은 되돌리지 않는다
     *   - 만든 후보는 처리 이력에 성공으로 남긴다
     *
     * @return 새로 만든 후보 수
     */
    public int createCandidates(Long orderId, LocalDateTime confirmedAt, List<ConfirmedOrderItemDto> items) {
        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        for (ConfirmedOrderItemDto item : items) {
            if (settlementCandidateRepository.existsByOrderItemId(item.orderItemId())) {
                log.info("정산 후보가 이미 있어 건너뜀 orderId={} orderItemId={}", orderId, item.orderItemId());
                continue;
            }
            if (!SettlementPolicy.isValidLineAmount(item.unitPrice(), item.quantity(), item.lineAmount())) {
                String reason = "orderId=%d orderItemId=%d 단가 %d × 수량 %d ≠ 항목 금액 %d 또는 0원 이하".formatted(
                        orderId, item.orderItemId(), item.unitPrice(), item.quantity(), item.lineAmount());
                settlementProcessHistoryRepository.save(SettlementProcessHistory.candidateRejected(
                        SettlementErrorCode.SETTLEMENT_CANDIDATE_AMOUNT_INVALID.resultCode(), reason, now));
                log.warn("정산 후보 생성 거절 {}", reason);
                continue;
            }
            SettlementCandidate candidate = settlementCandidateRepository.save(SettlementCandidate.create(orderId,
                    item.orderItemId(), item.sellerId(), item.lineAmount(), SettlementPolicy.FEE_RATE, confirmedAt));
            settlementProcessHistoryRepository.save(SettlementProcessHistory.candidateCreated(candidate.getId(), now));
            created++;
        }
        log.info("정산 후보 생성 orderId={} created={} received={}", orderId, created, items.size());
        return created;
    }
}