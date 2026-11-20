package com.backend.boundedContext.settlement.out;

import java.time.LocalDateTime;

/**
 * 엑셀 다운로드용 지급 완료(PAID) 정산서 한 줄
 * 엔티티 대신 필요한 값만 읽어 영속성 컨텍스트에 쌓이지 않게 한다(행이 많아도 메모리가 늘지 않음)
 */
public record SettlementExportRow(
        Long id,
        String settlementMonth,
        Long sellerId,
        long totalSalePrice,
        long totalFee,
        long payoutAmount,
        int itemCount,
        LocalDateTime paidAt,
        Long walletTxId
) {
}
