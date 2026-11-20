package com.backend.boundedContext.chat.app.dto;

import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.domain.ChatRole;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 대화의 메시지 하나
 *
 * @param role       USER(질문), ASSISTANT(답변)
 * @param answerType 답변을 만든 방식. 질문이면 null
 * @param sources    답변 근거 FAQ(지금 FAQ 기준 카테고리·질문). 질문이거나 NO_MATCH면 빈 목록
 */
public record ChatMessageDto(
        Long messageId,
        ChatRole role,
        String content,
        ChatAnswerType answerType,
        List<FaqSummaryDto> sources,
        LocalDateTime createdAt
) {}
