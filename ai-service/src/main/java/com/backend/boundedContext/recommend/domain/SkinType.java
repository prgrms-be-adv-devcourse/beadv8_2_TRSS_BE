package com.backend.boundedContext.recommend.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 설문 피부 타입 */
@Getter
@RequiredArgsConstructor
public enum SkinType {
    DRY("건성"),
    NORMAL("중성"),
    OILY("지성"),
    COMBINATION("복합성"),
    UNKNOWN("잘 모르겠어요");   // 크리에이터 추천에서 타입 일치로 보지 않는다

    private final String label;
}
