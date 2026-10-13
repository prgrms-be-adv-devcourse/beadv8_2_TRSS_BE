package com.backend.shared.payment.dto;

import java.time.LocalDateTime;

// 주문 결제 요청. idempotencyKey는 결제 시도마다 새로 만든다 (같은 키로 다시 호출하면 처음 결과를 반환)
public record PayCommand(Long orderId, Long buyerId, long amount,
                         LocalDateTime orderExpiresAt, String idempotencyKey) { }
