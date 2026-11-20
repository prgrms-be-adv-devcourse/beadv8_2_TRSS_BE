package com.backend.boundedContext.faq.in.dto;

import com.backend.boundedContext.faq.domain.FaqCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 질문·답변은 앞뒤 공백을 지우고 저장함
 *
 * @param visible false면 챗봇 검색에서 빠짐
 */
public record FaqCreateRequest(
        @NotNull FaqCategory category,
        @NotBlank @Size(max = 200) String question,
        @NotBlank @Size(max = 2000) String answer,
        @NotNull Boolean visible
) {}
