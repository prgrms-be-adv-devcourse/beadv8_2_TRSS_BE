package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.in.dto.SettlementPreviewResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementPreviewSellerResponse;
import com.backend.boundedContext.settlement.out.SettlementAmountTotalsRow;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.boundedContext.settlement.out.SettlementSellerAmountRow;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 정산 미리보기(저장하지 않음)
 *
 * 대상월 월 정산 Job을 지금 실행하면 집계될 후보(READY, 정산월 ≤ 대상월)를 판매자별로 더해 보여 준다.
 * 집계 Step과 같은 조건이라, 미리보기 금액 = 실제 집계 금액이다(그 사이 후보가 새로 생기거나 제외되지 않으면).
 * 판매자 이름·상태는 MemberApi.getSellers로 한 번에 가져오고, 대상월 정산서가 이미 있으면 그 상태도 함께 보여 준다.
 */
@Component
@RequiredArgsConstructor
public class SettlementGetPreviewUseCase {

    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementRepository settlementRepository;
    private final MemberApi memberApi;

    public SettlementPreviewResponse getPreview(YearMonth targetMonth, Pageable pageable) {
        String month = targetMonth.toString();
        SettlementAmountTotalsRow totals = settlementCandidateRepository.sumAggregatableTotals(month);
        // group by 쿼리에 정렬을 덧붙이지 않도록 정렬 없는 PageRequest로 넘긴다(쿼리가 판매자 ID 순으로 정렬)
        Page<SettlementSellerAmountRow> rows = settlementCandidateRepository.sumAggregatableGroupBySeller(month,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));

        List<Long> sellerIds = rows.getContent().stream().map(SettlementSellerAmountRow::sellerId).toList();
        Map<Long, SellerDto> sellers = sellerIds.isEmpty() ? Map.of()
                : memberApi.getSellers(sellerIds).stream()
                .collect(Collectors.toMap(SellerDto::sellerId, Function.identity(), (a, b) -> a));
        Map<Long, Settlement> existing = sellerIds.isEmpty() ? Map.of()
                : settlementRepository.findBySettlementMonthAndSellerIdIn(month, sellerIds).stream()
                .collect(Collectors.toMap(Settlement::getSellerId, Function.identity(), (a, b) -> a));

        PageResponse<SettlementPreviewSellerResponse> page = PageResponse.from(rows,
                row -> toSellerResponse(row, sellers.get(row.sellerId()), existing.get(row.sellerId())));
        return new SettlementPreviewResponse(month, totals.sellerCount(), totals.itemCount(), totals.saleTotal(),
                totals.feeTotal(), totals.payoutTotal(), page);
    }

    private static SettlementPreviewSellerResponse toSellerResponse(SettlementSellerAmountRow row, SellerDto seller,
                                                                    Settlement existing) {
        return new SettlementPreviewSellerResponse(
                row.sellerId(),
                seller == null ? null : seller.businessName(),
                seller == null ? null : seller.status(),
                seller != null && seller.status() == SellerStatus.SANCTIONED,
                existing == null ? null : existing.getId(),
                existing == null ? null : existing.getStatus(),
                existing != null && !existing.canAcceptCandidates(),
                row.itemCount(),
                row.carriedOverCount() == null ? 0L : row.carriedOverCount(),
                row.saleTotal(),
                row.feeTotal(),
                row.payoutTotal());
    }
}