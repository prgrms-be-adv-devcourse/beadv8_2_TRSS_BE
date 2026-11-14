package com.backend.boundedContext.recommend.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 추천 컨텍스트 오류 코드
 * resultCode는 "{status}-{상수 이름}"으로 자동 생성
 * (예: 404-SURVEY_NOT_FOUND)
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum RecommendErrorCode implements ErrorCode {

    SURVEY_NOT_FOUND(404, "설문을 아직 하지 않았습니다."),
    SURVEY_ANSWER_INVALID(400, "설문 답변이 올바르지 않습니다.");

    private final int status;
    private final String msg;
}
