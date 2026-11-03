package com.backend.shared.payment.dto;

// amount = 그 주문에서 이 판매자 몫의 판매 금액 합(지급액 + 수수료)
public record SettledOrderAmountDto(
        Long orderId,
        long amount
) {
}
