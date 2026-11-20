package com.backend.boundedContext.chat.app.dto;

import com.backend.boundedContext.chat.domain.ChatConversation;

import java.time.LocalDateTime;

/**
 * 내 대화 목록의 한 줄
 *
 * @param title 첫 질문(100자까지)
 */
public record ChatConversationDto(
        Long conversationId,
        String title,
        LocalDateTime lastMessageAt
) {
    public static ChatConversationDto from(ChatConversation conversation) {
        return new ChatConversationDto(conversation.getId(), conversation.getTitle(), conversation.getLastMessageAt());
    }
}
