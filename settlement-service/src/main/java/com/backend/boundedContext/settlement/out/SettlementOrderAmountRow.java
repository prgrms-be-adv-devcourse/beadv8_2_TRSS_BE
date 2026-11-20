package com.backend.boundedContext.settlement.out;

/**
 * 정산서에 묶인 후보를 주문별로 더한 조회 결과
 * 결제 컨텍스트가 주문별 홀딩에서 얼마를 꺼낼지 알려 줄 때 쓴다
 * JPQL sum()은 Long을 돌려주므로 필드도 Long
 */
public record SettlementOrderAmountRow(Long orderId, Long amount) {
}