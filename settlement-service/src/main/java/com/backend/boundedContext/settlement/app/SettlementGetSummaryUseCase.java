package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementSummaryResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementSummaryResponse;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.boundedContext.settlement.out.SettlementStatusAmountRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 정산 화면 위 요약 카드
 *
 * 관리자: 조회 조건(정산월 또는 기간·판매자)의 상태별 건수와 지급 완료 금액. 상태 탭과 관계없이 모든 상태를 센다
 * 판매자: 조회 기간의 본인 지급 완료 건수·금액과 가장 최근 지급 정산서
 * 상태별로 한 번에 묶어 세므로(group by) 정산서가 많아도 쿼리는 1번이다
 */
@Component
@RequiredArgsConstructor
public class SettlementGetSummaryUseCase {

    private final SettlementRepository settlementRepository;
    private final SettlementSupport settlementSupport;
    private final SettlementPolicy settlementPolicy;

    /** month 또는 기간(from~to) 중 하나. 둘 다 없으면 전체 */
    public SettlementSummaryResponse getSummary(String month, YearMonth from, YearMonth to, Long sellerId) {
        settlementPolicy.validateMonthFilter(month, from, to);
        String fromMonth = month != null ? month : from == null ? null : from.toString();
        String toMonth = month != null ? month : to == null ? null : to.toString();
        List<SettlementStatusAmountRow> rows = settlementRepository.sumByStatus(fromMonth, toMonth, sellerId);

        long retrying = countOf(rows, SettlementStatus.FAILED);
        long manual = countOf(rows, SettlementStatus.MANUAL_REQUIRED);
        long total = rows.stream().mapToLong(row -> zero(row.settlementCount())).sum();
        return new SettlementSummaryResponse(total, countOf(rows, SettlementStatus.PAID),
                payoutOf(rows, SettlementStatus.PAID),
                countOf(rows, SettlementStatus.READY) + countOf(rows, SettlementStatus.PROCESSING), retrying + manual,
                retrying, manual, countOf(rows, SettlementStatus.HOLD));
    }

    /** 판매자 본인. 기간(from~to)은 필수(화면 기본값: 최근 6개월). 판매자 신청 기록이 없으면 0건 */
    public SellerSettlementSummaryResponse getSellerSummary(Long memberId, YearMonth from, YearMonth to) {
        settlementPolicy.validateMonthRange(from, to);
        Optional<Long> sellerId = settlementSupport.findSellerId(memberId);
        if (sellerId.isEmpty()) return new SellerSettlementSummaryResponse(0L, 0L, null);

        List<SettlementStatusAmountRow> rows =
                settlementRepository.sumByStatus(from.toString(), to.toString(), sellerId.get());
        SellerSettlementSummaryResponse.LatestPaid latestPaid = settlementRepository
                .findFirstBySellerIdAndStatusOrderByPaidAtDesc(sellerId.get(), SettlementStatus.PAID)
                .map(SellerSettlementSummaryResponse.LatestPaid::from)
                .orElse(null);
        return new SellerSettlementSummaryResponse(countOf(rows, SettlementStatus.PAID),
                payoutOf(rows, SettlementStatus.PAID), latestPaid);
    }

    private static long countOf(List<SettlementStatusAmountRow> rows, SettlementStatus status) {
        return rows.stream().filter(row -> row.status() == status).mapToLong(row -> zero(row.settlementCount())).sum();
    }

    private static long payoutOf(List<SettlementStatusAmountRow> rows, SettlementStatus status) {
        return rows.stream().filter(row -> row.status() == status).mapToLong(row -> zero(row.payoutTotal())).sum();
    }

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }
}
