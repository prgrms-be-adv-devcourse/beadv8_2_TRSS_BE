package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.shared.member.dto.SellerStatus;

/**
 * 관리자 정산 미리보기 판매자 1명분
 *
 * willHold: 판매자가 관리자 제재(SANCTIONED) 상태라 집계하면 정산서가 HOLD로 만들어진다
 * existingSettlementStatus: 대상월 정산서가 이미 있으면 그 상태(없으면 null)
 * carriedToNextMonth: 이미 있는 정산서가 지급 진행 이후(PAID·FAILED·MANUAL_REQUIRED)라 후보가 다음 달로 이월된다
 * carriedOverCount: 정산월이 대상월보다 이전인(지난달에서 이월돼 온) 후보 수
 * businessName·sellerStatus: 회원 컨텍스트에 판매자가 없으면 null
 */
public record SettlementPreviewSellerResponse(
        Long sellerId,
        String businessName,
        SellerStatus sellerStatus,
        boolean willHold,
        Long existingSettlementId,
        SettlementStatus existingSettlementStatus,
        boolean carriedToNextMonth,
        long itemCount,
        long carriedOverCount,
        long totalSalePrice,
        long totalFee,
        long payoutAmount
) {
}