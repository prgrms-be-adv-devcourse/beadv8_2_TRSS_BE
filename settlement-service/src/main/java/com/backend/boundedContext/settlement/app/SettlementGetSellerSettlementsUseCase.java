package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementSellerStatus;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementResponse;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 판매자 본인 정산서 목록 (정산월·상태 필터, 최근 정산월부터)
 * 판매자 화면 상태(SettlementSellerStatus)로 거르면 그 상태에 속한 정산서 상태들로 바꿔 조회한다
 * (예: DELAYED → FAILED·MANUAL_REQUIRED). 실패 사유는 응답에 넣지 않는다
 */
@Component
@RequiredArgsConstructor
public class SettlementGetSellerSettlementsUseCase {

    private final SettlementSupport settlementSupport;

    public PageResponse<SellerSettlementResponse> getSellerSettlements(Long memberId, String month,
                                                                       SettlementSellerStatus status,
                                                                       Pageable pageable) {
        Optional<Long> sellerId = settlementSupport.findSellerId(memberId);
        if (sellerId.isEmpty()) {
            // 판매자 신청 기록이 없으면 정산서도 없다(SELLER 권한은 승인된 판매자에게만 생기므로 실제로는 거의 없음)
            return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0L, 0);
        }
        return PageResponse.from(settlementSupport.findSellerSettlements(sellerId.get(), month,
                        status == null ? null : status.getStatuses(), pageable),
                SellerSettlementResponse::from);
    }
}