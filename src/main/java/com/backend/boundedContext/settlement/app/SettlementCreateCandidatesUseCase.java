package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
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

    /**
     * 구매 확정된 주문 항목마다 정산 후보(READY)를 만든다.
     * 같은 이벤트가 다시 와도 결과가 같도록 order_item_id로 먼저 확인하고 이미 있으면 건너뛴다.
     * @return 새로 만든 후보 수 */
    public int createCandidates(Long orderId, LocalDateTime confirmedAt, List<ConfirmedOrderItemDto> items) {
        int created = 0;
        for (ConfirmedOrderItemDto item : items) {
            if (settlementCandidateRepository.existsByOrderItemId(item.orderItemId())) {
                log.info("정산 후보가 이미 있어 건너뜀 orderId={} orderItemId={}", orderId, item.orderItemId());
                continue;
            }
            settlementCandidateRepository.save(SettlementCandidate.create(orderId, item.orderItemId(),
                    item.sellerId(), item.lineAmount(), SettlementPolicy.FEE_RATE, confirmedAt));
            created++;
        }
        log.info("정산 후보 생성 orderId={} created={} received={}", orderId, created, items.size());
        return created;
    }
}
