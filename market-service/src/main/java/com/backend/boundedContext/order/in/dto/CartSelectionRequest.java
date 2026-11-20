package com.backend.boundedContext.order.in.dto;

import jakarta.validation.constraints.NotNull;

// 장바구니 전체 선택·해제 요청
public record CartSelectionRequest(
        @NotNull Boolean selected
) {
}
