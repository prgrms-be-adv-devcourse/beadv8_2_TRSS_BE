package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.out.FaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

// 노출 중인 FAQ 질문 목록. 챗봇이 검색어를 FAQ 표현에 맞춰 다시 쓸 때 씀(FAQ는 수십 건)
@Service
@RequiredArgsConstructor
public class FaqGetVisibleQuestionsUseCase {

    private final FaqRepository faqRepository;

    public List<String> getVisibleQuestions() {
        return faqRepository.findVisibleQuestions();
    }
}
