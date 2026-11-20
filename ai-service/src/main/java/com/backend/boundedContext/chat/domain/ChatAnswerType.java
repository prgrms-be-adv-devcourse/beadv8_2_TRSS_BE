package com.backend.boundedContext.chat.domain;

// 답변을 만든 방식
public enum ChatAnswerType {
    AI,          // 근거 FAQ로 LLM이 만든 답변
    FAQ,         // LLM이 실패·시간 초과라 가장 비슷한 FAQ 답변을 그대로 줌
    NO_MATCH     // FAQ로 답할 수 없어 고객센터를 안내함. 관련 FAQ가 없으면 LLM을 부르지 않고 고정 문구, 찾은 FAQ로도 답할 수 없으면 LLM 문구. 근거 FAQ 없음
}
