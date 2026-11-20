package com.backend.boundedContext.faq.app.dto;

import com.backend.boundedContext.faq.domain.FaqCategory;

import java.util.List;

/**
 * FAQ 의미 기반 검색 결과. 유사도 높은 순이고, 기준값보다 낮은 FAQ는 빠짐
 *
 * @param faqs 비어 있으면 질문과 관련된 FAQ가 없음
 */
public record FaqSearchDto(List<Item> faqs) {

    /**
     * @param categoryLabel 화면 표시용 카테고리 이름 (예: 반품·환불)
     * @param similarity    질문과의 코사인 유사도 (1에 가까울수록 비슷함)
     */
    public record Item(Long faqId, FaqCategory category, String categoryLabel, String question, String answer, double similarity) {
    }
}
