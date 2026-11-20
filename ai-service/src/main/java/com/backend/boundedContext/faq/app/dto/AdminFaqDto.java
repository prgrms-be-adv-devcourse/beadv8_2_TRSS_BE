package com.backend.boundedContext.faq.app.dto;

import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;

import java.time.LocalDateTime;

/**
 * 관리자 화면의 FAQ 한 건
 *
 * @param categoryLabel 화면 표시용 카테고리 이름 (예: 반품·환불)
 * @param visible       false면 챗봇 검색에서 빠짐
 * @param embedded      검색 반영 여부. false면 아직 반영 전: 보통 수 초 안에 반영되고, OpenAI 장애 중이면 복구될 때까지 false
 */
public record AdminFaqDto(Long faqId, FaqCategory category, String categoryLabel, String question, String answer,
                          boolean visible, boolean embedded, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static AdminFaqDto of(Faq faq, boolean embedded) {
        return new AdminFaqDto(faq.getId(), faq.getCategory(), faq.getCategory().getLabel(), faq.getQuestion(),
                faq.getAnswer(), faq.isVisible(), embedded, faq.getCreatedAt(), faq.getUpdatedAt());
    }
}
