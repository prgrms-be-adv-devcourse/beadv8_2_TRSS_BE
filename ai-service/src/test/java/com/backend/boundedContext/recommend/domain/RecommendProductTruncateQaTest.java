package com.backend.boundedContext.recommend.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.backend.boundedContext.recommend.domain.RecommendProduct.EMBEDDING_DESCRIPTION_MAX_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA 보강(TRSS-300): 설명 자르기(서로게이트 경계)와 재시도 정책 값.
 */
class RecommendProductTruncateQaTest {

    private static final String EMOJI = "😀";   // 두 char로 된 글자

    @Test
    @DisplayName("2,000자 이하는 그대로: 기존 상품의 임베딩 글·해시가 바뀌지 않는다(대량 재임베딩 없음)")
    void shortUnchanged() {
        String exact = "가".repeat(EMBEDDING_DESCRIPTION_MAX_LENGTH);
        assertThat(RecommendProduct.truncate(exact, EMBEDDING_DESCRIPTION_MAX_LENGTH)).isSameAs(exact);
        assertThat(RecommendProduct.truncate("", EMBEDDING_DESCRIPTION_MAX_LENGTH)).isEmpty();
    }

    @Test
    @DisplayName("서로게이트 쌍이 경계에 걸리면 반으로 자르지 않고 그 글자를 뺀다(1,999자)")
    void surrogateOnBoundary() {
        String text = "가".repeat(EMBEDDING_DESCRIPTION_MAX_LENGTH - 1) + EMOJI + "끝";

        String truncated = RecommendProduct.truncate(text, EMBEDDING_DESCRIPTION_MAX_LENGTH);

        assertThat(truncated).hasSize(EMBEDDING_DESCRIPTION_MAX_LENGTH - 1);
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("서로게이트 쌍이 경계 안에 다 들어가면 그대로 둔다")
    void surrogateInside() {
        String text = "가".repeat(EMBEDDING_DESCRIPTION_MAX_LENGTH - 2) + EMOJI + "끝";

        String truncated = RecommendProduct.truncate(text, EMBEDDING_DESCRIPTION_MAX_LENGTH);

        assertThat(truncated).hasSize(EMBEDDING_DESCRIPTION_MAX_LENGTH).endsWith(EMOJI);
    }

    @Test
    @DisplayName("이모지로만 된 긴 설명도 짝 없는 서로게이트 없이 자른다 → UTF-8 해시·요청이 깨지지 않음")
    void emojiOnly() {
        String text = EMOJI.repeat(3_000);

        String truncated = RecommendProduct.truncate(text, EMBEDDING_DESCRIPTION_MAX_LENGTH);

        assertThat(truncated).isEqualTo(EMOJI.repeat(EMBEDDING_DESCRIPTION_MAX_LENGTH / 2));
        assertThat(truncated.codePoints().noneMatch(cp -> Character.isSurrogate((char) cp) && cp <= 0xFFFF)).isTrue();
    }

    @Test
    @DisplayName("정책: n번째 실패 뒤 n번째 간격, 넘으면 마지막 간격, 0 이하도 첫 간격")
    void retryDelay() {
        RecommendPolicy policy = new RecommendPolicy(20, 0.05, 0.05, List.of(1, 5, 30, 60), 3);

        assertThat(policy.embeddingRetryDelay(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.embeddingRetryDelay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.embeddingRetryDelay(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.embeddingRetryDelay(4)).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.embeddingRetryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("정책: 잘못된 설정(빈 목록, 0 이하 간격, 알림 기준 0)은 기동 시 거절")
    void invalidPolicy() {
        assertThatThrownBy(() -> new RecommendPolicy(20, 0.05, 0.05, List.of(), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendPolicy(20, 0.05, 0.05, List.of(1, 0), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendPolicy(20, 0.05, 0.05, List.of(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
