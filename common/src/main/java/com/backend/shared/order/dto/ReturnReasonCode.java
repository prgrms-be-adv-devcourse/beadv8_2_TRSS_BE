package com.backend.shared.order.dto;

// 반품 사유. 구매자가 반품을 요청할 때 고르고(market), 반품 승인 이벤트(OrderReturnedEvent)에 실려
// 결제가 환불 금액을 나눌 때 쓴다 (단순 변심만 처음 배송비를 돌려주지 않고 반품 배송비를 뺀다)
public enum ReturnReasonCode {
    CHANGE_OF_MIND,     // 단순 변심
    DEFECTIVE,          // 불량·파손
    OTHER               // 기타 (상세 필수)
}
