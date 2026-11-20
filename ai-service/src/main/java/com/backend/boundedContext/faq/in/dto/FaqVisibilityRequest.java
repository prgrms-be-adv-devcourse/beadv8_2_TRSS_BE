package com.backend.boundedContext.faq.in.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param visible false면 챗봇 검색에서 빠짐
 */
public record FaqVisibilityRequest(
        @NotNull Boolean visible
) {}
