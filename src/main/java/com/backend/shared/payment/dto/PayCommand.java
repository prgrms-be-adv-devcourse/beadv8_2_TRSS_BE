package com.backend.shared.payment.dto;

import java.time.LocalDateTime;

// 주문 결제 요청. idempotencyKey는 결제 시도마다 새로 만든다 (같은 키로 다시 호출하면 처음 결과를 반환)
public record PayCommand(
        Long orderId,
        String orderNumber,  // 결제 건에 함께 저장해 관리자 결제 조회에서 보여 주고 검색 (주문번호는 바뀌지 않음)
        Long buyerId,
        long amount,
        LocalDateTime orderExpiresAt,
        String idempotencyKey
) {
    // 주문번호 없이 만드는 생성자 (orderNumber = null). 주문 쪽이 orderNumber를 넘기도록 바뀌기 전까지 기존 호출을 유지하려고 둠
    // TODO 주문 쪽(OrderPayOrderUseCase)이 위 생성자로 orderNumber를 넘기면 삭제
    public PayCommand(Long orderId, Long buyerId, long amount, LocalDateTime orderExpiresAt, String idempotencyKey) {
        this(orderId, null, buyerId, amount, orderExpiresAt, idempotencyKey);
    }
}
