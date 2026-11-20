package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.out.FaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 관리자 FAQ 등록. 임베딩은 비워 두고 스케줄러가 채움 */
@Service
@RequiredArgsConstructor
public class FaqCreateUseCase {

    private final FaqRepository faqRepository;

    public Long create(FaqCategory category, String question, String answer, boolean visible) {
        return faqRepository.save(Faq.create(category, question, answer, visible)).getId();
    }
}
