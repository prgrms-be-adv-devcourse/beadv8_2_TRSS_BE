package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementAdminStatusGroup;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 관리자 정산서 목록 (정산월·상태·판매자 필터, 최신순) + 판매자 상호명
 * 한 페이지에 나온 판매자들의 상호명을 SettlementMemberPort.getSellers로 한 번에 붙인다(판매자마다 조회하지 않음).
 * 회원 컨텍스트에 없는 판매자는 businessName이 null이다(미리보기와 같은 규칙)
 */
@Component
@RequiredArgsConstructor
public class SettlementGetSettlementListUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementPolicy settlementPolicy;

    /**
     * month 또는 기간(from~to, 최대 maxSearchMonths개월) 중 하나로 거른다. 둘 다 없으면 전체
     * 상태는 status(정산서 상태 1개)가 있으면 그것으로, 없으면 group(화면 탭: 처리 필요 = FAILED + MANUAL_REQUIRED 등)으로 거른다
     */
    public PageResponse<SettlementResponse> getSettlements(String month, YearMonth from, YearMonth to,
                                                           SettlementStatus status, SettlementAdminStatusGroup group,
                                                           Long sellerId, Pageable pageable) {
        settlementPolicy.validateMonthFilter(month, from, to);
        List<SettlementStatus> statuses = status != null ? List.of(status) : group != null ? group.getStatuses() : null;
        Page<Settlement> page = settlementSupport.findSettlements(month, from, to, statuses, sellerId, pageable);
        Map<Long, String> names = settlementSupport.findBusinessNames(
                page.getContent().stream().map(Settlement::getSellerId).distinct().toList());
        return PageResponse.from(page, s -> SettlementResponse.of(s, names.get(s.getSellerId())));
    }
}