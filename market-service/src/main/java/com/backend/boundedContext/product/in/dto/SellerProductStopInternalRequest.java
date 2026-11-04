package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotBlank;

public record SellerProductStopInternalRequest(@NotBlank String reason) {
}
