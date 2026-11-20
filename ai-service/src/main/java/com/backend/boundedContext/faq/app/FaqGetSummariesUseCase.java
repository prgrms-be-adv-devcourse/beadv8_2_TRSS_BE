package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;
import com.backend.boundedContext.faq.out.FaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// ID로 FAQ 요약을 찾음. 대화 기록의 근거 FAQ 표시에 씀(숨긴 FAQ도 포함, FAQ는 지우지 않음)
@Service
@RequiredArgsConstructor
public class FaqGetSummariesUseCase {

    private final FaqRepository faqRepository;

    public Map<Long, FaqSummaryDto> getSummaries(Collection<Long> faqIds) {
        if (faqIds.isEmpty()) return Map.of();

        return faqRepository.findAllById(faqIds).stream()
                .map(faq -> new FaqSummaryDto(faq.getId(), faq.getCategory(), faq.getCategory().getLabel(), faq.getQuestion()))
                .collect(Collectors.toMap(FaqSummaryDto::faqId, Function.identity()));
    }
}
