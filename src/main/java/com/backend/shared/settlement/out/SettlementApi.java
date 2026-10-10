package com.backend.shared.settlement.out;

/**
 * 구현체 {@link com.backend.boundedContext.settlement.in.SettlementApiAdapter}
 */
public interface SettlementApi {

    /**
     * 아직 지급되지 않은 정산 금액. 철회·탈퇴 검사용.
     * 구매 확정된 금액만 센다: READY·INCLUDED 정산 후보 중 PAID 정산서에 포함되지 않은 것.
     * 결제됐지만 아직 확정 전인 주문은 포함하지 않는다.
     */
    long getUnsettledAmount(Long sellerId);

    /**
     * 관리자 제재: 이 판매자의 정산을 HOLD로 바꾸고, 이후 만들어지는 정산서도 HOLD로 둔다
      */
    void holdSeller(Long sellerId, String reason);

}
