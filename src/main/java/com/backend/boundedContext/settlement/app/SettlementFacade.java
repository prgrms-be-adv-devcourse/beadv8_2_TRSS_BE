package com.backend.boundedContext.settlement.app;

import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementFacade {

    private final SettlementSupport settlementSupport;
    private final SettlementCreateCandidatesUseCase settlementCreateCandidatesUseCase;

    /**
     * 구매 확정된 주문 항목마다 정산 후보 생성
     * @return 새로 만든 후보 수 */
    @Transactional
    public int createCandidates(Long orderId, LocalDateTime confirmedAt, List<ConfirmedOrderItemDto> items) {
        return settlementCreateCandidatesUseCase.createCandidates(orderId, confirmedAt, items);
    }

    @Transactional(readOnly = true)
    public boolean hasAnyCandidate() {
        return settlementSupport.hasAnyCandidate();
    }
}

