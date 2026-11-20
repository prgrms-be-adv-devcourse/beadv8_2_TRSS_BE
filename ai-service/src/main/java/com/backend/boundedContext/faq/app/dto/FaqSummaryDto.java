package com.backend.boundedContext.faq.app.dto;

import com.backend.boundedContext.faq.domain.FaqCategory;

/**
 * 답변 근거로 보여 줄 FAQ 요약(답변 본문 제외)
 *
 * @param categoryLabel 화면 표시용 카테고리 이름 (예: 반품·환불)
 */
public record FaqSummaryDto(
        Long faqId,
        FaqCategory category,
        String categoryLabel,
        String question
) {}
