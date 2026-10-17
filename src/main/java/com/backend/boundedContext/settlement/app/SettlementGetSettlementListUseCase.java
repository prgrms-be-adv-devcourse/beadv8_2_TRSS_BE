package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 관리자 정산서 목록 (정산월·상태·판매자 필터, 최신순) + 판매자 상호명
 * 한 페이지에 나온 판매자들의 상호명을 MemberApi.getSellers로 한 번에 붙인다(판매자마다 조회하지 않음).
 * 회원 컨텍스트에 없는 판매자는 businessName이 null이다(미리보기와 같은 규칙)
 */
@Component
@RequiredArgsConstructor
public class SettlementGetSettlementListUseCase {

    private final SettlementSupport settlementSupport;

    public PageResponse<SettlementResponse> getSettlements(String month, SettlementStatus status, Long sellerId,
                                                           Pageable pageable) {
        Page<Settlement> page = settlementSupport.findSettlements(month, status, sellerId, pageable);
        Map<Long, String> names = settlementSupport.findBusinessNames(
                page.getContent().stream().map(Settlement::getSellerId).distinct().toList());
        return PageResponse.from(page, s -> SettlementResponse.of(s, names.get(s.getSellerId())));
    }
}