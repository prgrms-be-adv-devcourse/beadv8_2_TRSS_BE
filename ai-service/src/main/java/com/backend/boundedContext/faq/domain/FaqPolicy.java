package com.backend.boundedContext.faq.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// FAQ 컨텍스트 정책 값 (custom.faq.*)
@Component
@Getter
public class FaqPolicy {

    // 한 번에 임베딩하는 FAQ 수. OpenAI 호출 한 번에 묶어 보냄
    private final int embeddingBatchSize;

    // 검색에서 가져오는 FAQ 최대 수(유사도 높은 순)
    private final int searchTopK;

    // 이 코사인 유사도보다 낮은 FAQ는 질문과 관련 없다고 보고 버림
    private final double similarityThreshold;

    public FaqPolicy(@Value("${custom.faq.embeddingBatchSize}") int embeddingBatchSize,
                     @Value("${custom.faq.searchTopK}") int searchTopK,
                     @Value("${custom.faq.similarityThreshold}") double similarityThreshold) {
        if (embeddingBatchSize < 1) throw new IllegalArgumentException("custom.faq.embeddingBatchSize는 1 이상이어야 합니다: " + embeddingBatchSize);
        if (searchTopK < 1) throw new IllegalArgumentException("custom.faq.searchTopK는 1 이상이어야 합니다: " + searchTopK);
        if (similarityThreshold < -1 || similarityThreshold > 1) throw new IllegalArgumentException("custom.faq.similarityThreshold는 -1~1이어야 합니다: " + similarityThreshold);
        this.embeddingBatchSize = embeddingBatchSize;
        this.searchTopK = searchTopK;
        this.similarityThreshold = similarityThreshold;
    }
}
