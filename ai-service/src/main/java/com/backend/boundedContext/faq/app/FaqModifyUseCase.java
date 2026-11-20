package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.app.dto.AdminFaqDto;
import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.domain.FaqErrorCode;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.boundedContext.faq.out.FaqVectorRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 FAQ 수정·노출 변경. 삭제는 하지 않고 노출 끄기로 대신함(대화 기록의 근거 FAQ가 남고, 되돌릴 수 있음)
 */
@Service
@RequiredArgsConstructor
public class FaqModifyUseCase {

    private final FaqRepository faqRepository;
    private final FaqVectorRepository faqVectorRepository;

    /**
     * 질문·답변이 바뀌면 같은 트랜잭션에서 임베딩을 비움. 스케줄러가 새 글로 다시 채움.
     * 스케줄러가 옛 글로 임베딩하던 중이어도 해시가 달라 저장되지 않음
     */
    public AdminFaqDto modify(Long faqId, FaqCategory category, String question, String answer) {
        Faq faq = find(faqId);
        boolean sourceChanged = faq.modify(category, question, answer);
        if (sourceChanged) faqVectorRepository.clearEmbedding(faqId);
        return AdminFaqDto.of(faq, !sourceChanged && faqVectorRepository.isEmbedded(faqId));
    }

    public AdminFaqDto changeVisibility(Long faqId, boolean visible) {
        Faq faq = find(faqId);
        faq.changeVisibility(visible);
        return AdminFaqDto.of(faq, faqVectorRepository.isEmbedded(faqId));
    }

    private Faq find(Long faqId) {
        return faqRepository.findById(faqId).orElseThrow(() -> new DomainException(FaqErrorCode.FAQ_NOT_FOUND));
    }
}
