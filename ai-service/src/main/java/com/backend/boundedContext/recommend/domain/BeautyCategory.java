package com.backend.boundedContext.recommend.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 설문 관심 카테고리. label은 market 뷰티 소분류 이름과 같다 */
@Getter
@RequiredArgsConstructor
public enum BeautyCategory {
    SKINCARE("스킨케어"),
    SUNCARE("선케어"),
    MAKEUP("메이크업"),
    CLEANSING("클렌징"),
    MASK_PACK("마스크팩");

    private final String label;
}
