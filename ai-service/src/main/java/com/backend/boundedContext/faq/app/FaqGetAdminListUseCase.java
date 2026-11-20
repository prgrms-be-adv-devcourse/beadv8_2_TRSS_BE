package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.app.dto.AdminFaqDto;
import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.boundedContext.faq.out.FaqVectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 관리자 FAQ 목록. FAQ는 수십 건이라 페이지 없이 모두 줌. 숨긴 FAQ도 포함 */
@Service
@RequiredArgsConstructor
public class FaqGetAdminListUseCase {

    private final FaqRepository faqRepository;
    private final FaqVectorRepository faqVectorRepository;

    // category가 null이면 전체. 최근 등록 순
    public List<AdminFaqDto> getList(FaqCategory category) {
        Sort latest = Sort.by(Sort.Direction.DESC, "id");
        List<Faq> faqs = category == null ? faqRepository.findAll(latest) : faqRepository.findAllByCategory(category, latest);
        Set<Long> embeddedIds = faqVectorRepository.findEmbeddedIds();
        return faqs.stream().map(faq -> AdminFaqDto.of(faq, embeddedIds.contains(faq.getId()))).toList();
    }
}
