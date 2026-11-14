package com.backend.boundedContext.recommend.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeautyProfileTest {

    @Test
    @DisplayName("저장한 값은 코드표 순서로 돌려주고, 피하는 성분이 없으면(없어요) 빈 값")
    void create() {
        BeautyProfile profile = BeautyProfile.create(1L, SkinType.OILY,
                List.of(SkinConcern.PORE, SkinConcern.TROUBLE), List.of(CICA, NIACINAMIDE), List.of(),
                List.of(BeautyCategory.SUNCARE, BeautyCategory.SKINCARE));

        assertThat(profile.getSkinType()).isEqualTo(SkinType.OILY);
        assertThat(profile.getConcerns()).containsExactly(SkinConcern.TROUBLE, SkinConcern.PORE);
        assertThat(profile.getPreferredIngredients()).containsExactly(NIACINAMIDE, CICA);
        assertThat(profile.getAvoidedIngredients()).isEmpty();
        assertThat(profile.getInterestCategories()).containsExactly(BeautyCategory.SKINCARE, BeautyCategory.SUNCARE);
    }

    @Test
    @DisplayName("고민 4개는 SURVEY_ANSWER_INVALID")
    void tooManyConcerns() {
        assertInvalid(() -> BeautyProfile.create(1L, SkinType.DRY,
                List.of(SkinConcern.TROUBLE, SkinConcern.PORE, SkinConcern.DRYNESS, SkinConcern.SEBUM),
                List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("선호 칸에 피하는 성분 코드(FRAGRANCE), 피하는 칸에 선호 코드(NIACINAMIDE)는 SURVEY_ANSWER_INVALID")
    void wrongSide() {
        assertInvalid(() -> BeautyProfile.create(1L, SkinType.DRY, List.of(), List.of(FRAGRANCE), List.of(), List.of()));
        assertInvalid(() -> BeautyProfile.create(1L, SkinType.DRY, List.of(), List.of(), List.of(NIACINAMIDE), List.of()));
    }

    @Test
    @DisplayName("같은 코드를 두 번 보내면 SURVEY_ANSWER_INVALID")
    void duplicate() {
        assertInvalid(() -> BeautyProfile.create(1L, SkinType.DRY, List.of(SkinConcern.PORE, SkinConcern.PORE),
                List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("다시 저장하면 이전 답을 모두 덮어씀")
    void update() {
        BeautyProfile profile = BeautyProfile.create(1L, SkinType.OILY, List.of(SkinConcern.TROUBLE),
                List.of(CICA), List.of(ALCOHOL), List.of(BeautyCategory.SKINCARE));

        profile.update(SkinType.DRY, List.of(), List.of(), List.of(FRAGRANCE, PARABEN), List.of());

        assertThat(profile.getSkinType()).isEqualTo(SkinType.DRY);
        assertThat(profile.getConcerns()).isEmpty();
        assertThat(profile.getPreferredIngredients()).isEmpty();
        assertThat(profile.getAvoidedIngredients()).containsExactly(FRAGRANCE, PARABEN);
        assertThat(profile.getInterestCategories()).isEmpty();
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(RecommendErrorCode.SURVEY_ANSWER_INVALID.resultCode());
    }
}
