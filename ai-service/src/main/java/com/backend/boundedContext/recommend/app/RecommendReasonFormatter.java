package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.IngredientTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 추천 이유 문구. LLM 없이 겹친 항목을 이어 붙인다. 순서는 코드표 순서라 매번 같다.
 * 예: "나이아신아마이드 · 시카(병풀) 포함 · 관심 카테고리 스킨케어 · 향료 · 알코올(에탄올) 없음"
 */
final class RecommendReasonFormatter {

    static final String DEFAULT_REASON = "내 피부 프로필과 비슷한 상품";
    private static final String SEPARATOR = " · ";

    private RecommendReasonFormatter() {
    }

    /**
     * @param matchedIngredients 상품에 있는 내 선호 성분
     * @param matchedCategory    내 관심 카테고리면 그 카테고리, 아니면 null
     * @param avoided            내가 고른 피하는 성분 (필터를 통과했으므로 상품에 없다)
     */
    static String format(Set<IngredientTag> matchedIngredients, BeautyCategory matchedCategory, Set<IngredientTag> avoided) {
        List<String> parts = new ArrayList<>();
        if (!matchedIngredients.isEmpty()) parts.add(labels(matchedIngredients) + " 포함");
        if (matchedCategory != null) parts.add("관심 카테고리 " + matchedCategory.getLabel());
        if (!avoided.isEmpty()) parts.add(labels(avoided) + " 없음");
        return parts.isEmpty() ? DEFAULT_REASON : String.join(SEPARATOR, parts);
    }

    private static String labels(Set<IngredientTag> tags) {
        return tags.stream().sorted().map(IngredientTag::getLabel).collect(Collectors.joining(SEPARATOR));
    }
}
