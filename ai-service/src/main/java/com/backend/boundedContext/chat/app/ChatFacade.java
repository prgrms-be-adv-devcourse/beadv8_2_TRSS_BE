package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.app.dto.ChatConversationDto;
import com.backend.boundedContext.chat.app.dto.ChatMessageDto;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 챗봇 컨텍스트의 입구
 */
@Component
@RequiredArgsConstructor
public class ChatFacade {

    private final ChatAnswerUseCase chatAnswerUseCase;
    private final ChatGetConversationsUseCase chatGetConversationsUseCase;

    // FAQ 근거 답변. OpenAI(임베딩·채팅)를 부르므로 트랜잭션을 걸지 않음(대화 읽기·저장은 유스케이스가 짧게 나눠 묶음)
    public ChatAnswerDto answer(Long memberId, Long conversationId, String question) {
        return chatAnswerUseCase.answer(memberId, conversationId, question);
    }

    @Transactional(readOnly = true)
    public PageResponse<ChatConversationDto> getConversations(Long memberId, Pageable pageable) {
        return chatGetConversationsUseCase.getConversations(memberId, pageable);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> getMessages(Long memberId, Long conversationId) {
        return chatGetConversationsUseCase.getMessages(memberId, conversationId);
    }
}
