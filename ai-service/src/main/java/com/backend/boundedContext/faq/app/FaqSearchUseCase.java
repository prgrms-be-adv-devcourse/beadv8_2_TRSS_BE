package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto.Item;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.domain.FaqPolicy;
import com.backend.boundedContext.faq.out.FaqVectorRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 질문을 임베딩해 의미가 가까운 FAQ를 찾음. 질문 임베딩에 OpenAI를 부르므로 트랜잭션 밖에서 부름
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqSearchUseCase {

    private final EmbeddingModel embeddingModel;
    private final FaqPolicy faqPolicy;
    private final FaqVectorRepository faqVectorRepository;

    public FaqSearchDto search(String query) {
        float[] embedding;
        try {
            embedding = embeddingModel.embed(query);
        } catch (RuntimeException e) {
            log.warn("faq query embedding failed reason={}", e.toString());
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }

        List<Item> faqs = faqVectorRepository.search(embedding, faqPolicy.getSimilarityThreshold(), faqPolicy.getSearchTopK()).stream()
                .map(row -> {
                    FaqCategory category = FaqCategory.valueOf(row.category());
                    return new Item(row.faqId(), category, category.getLabel(), row.question(), row.answer(), row.similarity());
                })
                .toList();
        return new FaqSearchDto(faqs);
    }
}
