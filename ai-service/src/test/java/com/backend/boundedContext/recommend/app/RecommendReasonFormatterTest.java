package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.domain.BeautyCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendReasonFormatterTest {

    @Test
    @DisplayName("선호 성분 → 관심 카테고리 → 피하는 성분 순으로, 성분은 코드표 순서로 잇는다")
    void allParts() {
        String reason = RecommendReasonFormatter.format(Set.of(CICA, NIACINAMIDE), BeautyCategory.SKINCARE, Set.of(ALCOHOL, FRAGRANCE));

        assertThat(reason).isEqualTo("나이아신아마이드 · 시카(병풀) 포함 · 관심 카테고리 스킨케어 · 향료 · 알코올(에탄올) 없음");
    }

    @Test
    @DisplayName("겹친 게 하나뿐이면 그것만, 아무것도 없으면 기본 문구")
    void partialAndDefault() {
        assertThat(RecommendReasonFormatter.format(Set.of(), null, Set.of(PARABEN))).isEqualTo("파라벤 없음");
        assertThat(RecommendReasonFormatter.format(Set.of(), null, Set.of())).isEqualTo(RecommendReasonFormatter.DEFAULT_REASON);
    }
}
