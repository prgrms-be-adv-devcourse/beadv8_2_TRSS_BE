package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;

import java.time.LocalDateTime;

/**
 * 판매자 정산 화면 위 요약 카드
 * "다음 지급 예정액"은 기존 정산 예정 금액 API(/expected)를 쓴다
 *
 * @param paidCount   조회 기간 지급 완료 건수 ("지급 완료 N건 엑셀로 받기" 버튼에도 쓴다)
 * @param paidAmount  조회 기간 지급 완료 지급액 합
 * @param latestPaid  가장 최근에 지급된 정산서("지난 지급액" 카드). 없으면 null
 */
public record SellerSettlementSummaryResponse(
        long paidCount,
        long paidAmount,
        LatestPaid latestPaid
) {
    /** 가장 최근에 지급된 정산서 */
    public record LatestPaid(Long settlementId, String settlementMonth, long payoutAmount, LocalDateTime paidAt) {
        public static LatestPaid from(Settlement s) {
            return new LatestPaid(s.getId(), s.getSettlementMonth(), s.getPayoutAmount(), s.getPaidAt());
        }
    }
}
