package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.in.dto.SettlementExpectedMonthResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementExpectedResponse;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 판매자 정산 예정 금액: 구매 확정됐지만 아직 정산서에 묶이지 않은(READY) 후보를 정산월별로 더한다
 * 제외(EXCLUDED)·이미 묶인(INCLUDED) 후보는 넣지 않는다. 묶인 금액은 정산서 목록에서 본다
 * 정산월마다 예정 지급일(다가올 월 정산 Job 기준 추정)을 함께 준다
 */
@Component
@RequiredArgsConstructor
public class SettlementGetExpectedAmountsUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementPolicy settlementPolicy;

    public SettlementExpectedResponse getExpectedAmounts(Long memberId) {
        Optional<Long> sellerId = settlementSupport.findSellerId(memberId);
        if (sellerId.isEmpty()) return SettlementExpectedResponse.of(List.of());

        LocalDateTime now = LocalDateTime.now();
        List<SettlementExpectedMonthResponse> months = settlementCandidateRepository
                .sumReadyBySellerIdGroupByMonth(sellerId.get()).stream()
                .map(row -> new SettlementExpectedMonthResponse(row.settlementMonth(), row.itemCount(),
                        row.saleTotal(), row.feeTotal(), row.payoutTotal(),
                        settlementPolicy.expectedPayDate(row.settlementMonth(), now)))
                .toList();
        return SettlementExpectedResponse.of(months);
    }
}