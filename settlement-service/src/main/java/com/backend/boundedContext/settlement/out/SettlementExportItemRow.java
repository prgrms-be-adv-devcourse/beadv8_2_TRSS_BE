package com.backend.boundedContext.settlement.out;

import java.time.LocalDateTime;

/**
 * 엑셀 다운로드용 정산서 품목(정산 후보) 한 줄
 * settlementMonth는 품목이 처음 속한 정산월이라, 정산서 정산월과 다르면 이월된 품목이다
 */
public record SettlementExportItemRow(
        Long settlementId,
        Long orderId,
        Long orderItemId,
        long salePrice,
        int feeRate,
        long fee,
        long payoutAmount,
        LocalDateTime confirmedAt,
        String settlementMonth
) {
}
