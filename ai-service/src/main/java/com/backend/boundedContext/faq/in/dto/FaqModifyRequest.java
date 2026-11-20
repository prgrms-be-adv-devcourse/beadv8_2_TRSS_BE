package com.backend.boundedContext.faq.in.dto;

import com.backend.boundedContext.faq.domain.FaqCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 카테고리·질문·답변을 모두 보냄. 질문·답변이 바뀌면 다시 임베딩될 때까지 검색 반영 전(embedded=false)이 됨
 */
public record FaqModifyRequest(
        @NotNull FaqCategory category,
        @NotBlank @Size(max = 200) String question,
        @NotBlank @Size(max = 2000) String answer
) {}
