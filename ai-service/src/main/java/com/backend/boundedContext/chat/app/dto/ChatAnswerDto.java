package com.backend.boundedContext.chat.app.dto;

import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;

import java.util.List;

/**
 * 챗봇 답변
 *
 * @param conversationId 이 질문·답변이 저장된 대화. 이어서 물을 때 그대로 보냄
 * @param answerType     답변을 만든 방식 (AI, FAQ, NO_MATCH)
 * @param sources        답변 근거 FAQ(유사도 높은 순). NO_MATCH면 빈 목록
 */
public record ChatAnswerDto(
        Long conversationId,
        String answer,
        ChatAnswerType answerType,
        List<FaqSummaryDto> sources
) {}
