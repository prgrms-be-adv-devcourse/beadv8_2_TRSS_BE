package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqPolicy;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.boundedContext.faq.out.FaqVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 비어 있는 FAQ 임베딩을 OpenAI로 채움. 트랜잭션 밖에서 부름(외부 호출 동안 DB 연결을 잡지 않게).
 * 임베딩하는 동안 질문·답변이 바뀌었으면 저장하지 않고 다음 차례에 다시 만듦.
 * 실패하면 WARN만 남기고 그대로 둠. 다음 차례에 다시 시도함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqEmbedUseCase {

    private final EmbeddingModel embeddingModel;
    private final FaqPolicy faqPolicy;
    private final FaqRepository faqRepository;
    private final FaqVectorRepository faqVectorRepository;

    public void embedPending() {
        List<Long> ids = faqVectorRepository.findIdsToEmbed(faqPolicy.getEmbeddingBatchSize());
        if (ids.isEmpty()) return;

        List<Faq> faqs = faqRepository.findAllById(ids);
        if (faqs.isEmpty()) return;

        List<float[]> embeddings;
        try {
            embeddings = embeddingModel.embed(faqs.stream().map(Faq::embeddingSource).toList());
        } catch (RuntimeException e) {
            log.warn("faq embedding failed count={} reason={}", faqs.size(), e.toString());
            return;
        }

        int saved = 0;
        for (int i = 0; i < faqs.size(); i++) {
            Faq faq = faqs.get(i);
            if (faqVectorRepository.saveEmbedding(faq.getId(), faq.getEmbeddingSourceHash(), embeddings.get(i))) saved++;
        }
        log.info("faq embedding done count={} saved={}", faqs.size(), saved);
    }
}
