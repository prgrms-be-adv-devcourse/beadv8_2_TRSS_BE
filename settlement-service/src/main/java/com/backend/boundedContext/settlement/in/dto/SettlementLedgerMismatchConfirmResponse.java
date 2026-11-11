package com.backend.boundedContext.settlement.in.dto;

/**
 * 관리자 원장 재대조 불일치 확인 결과
 *
 * @param settlementId    확인한 정산서
 * @param releasedCount   그 판매자에게 다른 불일치가 남지 않아 자동 해제한(HOLD → READY) 원장 불일치 보류 정산서 수.
 *                        다른 불일치가 남아 있으면 0
 */
public record SettlementLedgerMismatchConfirmResponse(
        Long settlementId,
        int releasedCount
) {
}
