package com.backend.boundedContext.recommend.domain;

import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

// 추천 컨텍스트 정책 값 (custom.recommend.*)
@Component
@Getter
public class RecommendPolicy {

    // 한 번에 임베딩하는 상품·설문 수. OpenAI 호출 한 번에 묶어 보낸다
    private final int embeddingBatchSize;

    // 추천 점수 = 프로필과 상품 임베딩의 코사인 유사도 + 겹친 선호 성분 수 × 이 값 + 관심 카테고리면 이 값
    // 유사도(보통 0.1~0.6) 차이가 작을 때 순서를 가르는 정도의 크기로 둔다
    private final double preferredIngredientWeight;
    private final double interestCategoryWeight;

    // 임베딩이 실패한 상품·설문을 다시 시도하기까지 기다리는 시간(분). n번째 실패 뒤에는 n번째 값, 그 뒤로는 마지막 값
    @Getter(AccessLevel.NONE)
    private final List<Integer> embeddingRetryDelayMinutes;

    // 연속 실패가 이 횟수에 처음 닿으면 ERROR 로그를 한 번 남긴다(Slack 알림)
    private final int embeddingFailureAlertAttempts;

    public RecommendPolicy(@Value("${custom.recommend.embeddingBatchSize}") int embeddingBatchSize,
                           @Value("${custom.recommend.preferredIngredientWeight}") double preferredIngredientWeight,
                           @Value("${custom.recommend.interestCategoryWeight}") double interestCategoryWeight,
                           @Value("${custom.recommend.embeddingRetryDelayMinutes}") List<Integer> embeddingRetryDelayMinutes,
                           @Value("${custom.recommend.embeddingFailureAlertAttempts}") int embeddingFailureAlertAttempts) {
        if (embeddingBatchSize < 1) throw new IllegalArgumentException("custom.recommend.embeddingBatchSize는 1 이상이어야 합니다: " + embeddingBatchSize);
        if (preferredIngredientWeight < 0 || interestCategoryWeight < 0) throw new IllegalArgumentException("추천 가점은 0 이상이어야 합니다");
        if (embeddingRetryDelayMinutes.isEmpty() || embeddingRetryDelayMinutes.stream().anyMatch(minutes -> minutes < 1)) {
            throw new IllegalArgumentException("custom.recommend.embeddingRetryDelayMinutes는 1 이상인 값이 하나 이상 있어야 합니다: " + embeddingRetryDelayMinutes);
        }
        if (embeddingFailureAlertAttempts < 1) throw new IllegalArgumentException("custom.recommend.embeddingFailureAlertAttempts는 1 이상이어야 합니다: " + embeddingFailureAlertAttempts);
        this.embeddingBatchSize = embeddingBatchSize;
        this.preferredIngredientWeight = preferredIngredientWeight;
        this.interestCategoryWeight = interestCategoryWeight;
        this.embeddingRetryDelayMinutes = List.copyOf(embeddingRetryDelayMinutes);
        this.embeddingFailureAlertAttempts = embeddingFailureAlertAttempts;
    }

    /** attempts번째 연속 실패 뒤 다시 시도하기까지 기다리는 시간 */
    public Duration embeddingRetryDelay(int attempts) {
        int index = Math.min(Math.max(attempts, 1), embeddingRetryDelayMinutes.size()) - 1;
        return Duration.ofMinutes(embeddingRetryDelayMinutes.get(index));
    }
}
