package com.backend.boundedContext.payment.out.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 토스 오류 응답 본문 (예: {"code":"REJECT_CARD_PAYMENT","message":"한도초과 혹은 잔액부족으로 결제에 실패했습니다."})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossErrorResponse(
        String code,
        String message
) {}
