package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.app.dto.AdminFaqDto;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;
import com.backend.boundedContext.faq.domain.FaqCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

// FAQ 컨텍스트의 입구
@Component
@RequiredArgsConstructor
public class FaqFacade {

    private final FaqEmbedUseCase faqEmbedUseCase;
    private final FaqSearchUseCase faqSearchUseCase;
    private final FaqCreateUseCase faqCreateUseCase;
    private final FaqModifyUseCase faqModifyUseCase;
    private final FaqGetAdminListUseCase faqGetAdminListUseCase;
    private final FaqGetSummariesUseCase faqGetSummariesUseCase;
    private final FaqGetVisibleQuestionsUseCase faqGetVisibleQuestionsUseCase;

    // 비어 있는 FAQ 임베딩을 채움. OpenAI를 부르므로 트랜잭션을 걸지 않음
    public void embedPending() {
        faqEmbedUseCase.embedPending();
    }

    // 질문과 의미가 가까운 FAQ. 질문 임베딩에 OpenAI를 부르므로 트랜잭션을 걸지 않음
    public FaqSearchDto search(String query) {
        return faqSearchUseCase.search(query);
    }

    // 노출 중인 FAQ 질문(등록 순)
    @Transactional(readOnly = true)
    public List<String> getVisibleQuestions() {
        return faqGetVisibleQuestionsUseCase.getVisibleQuestions();
    }

    // ID로 FAQ 요약. 없는 ID는 빠짐
    @Transactional(readOnly = true)
    public Map<Long, FaqSummaryDto> getSummaries(Collection<Long> faqIds) {
        return faqGetSummariesUseCase.getSummaries(faqIds);
    }

    // 관리자 FAQ 목록. category가 null이면 전체
    @Transactional(readOnly = true)
    public List<AdminFaqDto> getAdminList(FaqCategory category) {
        return faqGetAdminListUseCase.getList(category);
    }

    @Transactional
    public Long create(FaqCategory category, String question, String answer, boolean visible) {
        return faqCreateUseCase.create(category, question, answer, visible);
    }

    @Transactional
    public AdminFaqDto modify(Long faqId, FaqCategory category, String question, String answer) {
        return faqModifyUseCase.modify(faqId, category, question, answer);
    }

    @Transactional
    public AdminFaqDto changeVisibility(Long faqId, boolean visible) {
        return faqModifyUseCase.changeVisibility(faqId, visible);
    }
}
