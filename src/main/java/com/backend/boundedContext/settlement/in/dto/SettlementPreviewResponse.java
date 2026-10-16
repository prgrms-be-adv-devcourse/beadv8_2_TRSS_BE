package com.backend.boundedContext.settlement.in.dto;

import com.backend.global.page.PageResponse;

/**
 * 관리자 정산 미리보기(저장하지 않음)
 * 대상월 월 정산 Job을 지금 실행하면 집계될 후보(READY, 정산월 ≤ 대상월)의 전체 합계 + 판매자별 내역(페이지)
 */
public record SettlementPreviewResponse(
        String targetMonth,
        long sellerCount,
        long itemCount,
        long totalSalePrice,
        long totalFee,
        long payoutAmount,
        PageResponse<SettlementPreviewSellerResponse> sellers
) {
}