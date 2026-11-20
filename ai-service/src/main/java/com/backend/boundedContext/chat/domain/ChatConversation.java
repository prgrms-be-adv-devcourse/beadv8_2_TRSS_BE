package com.backend.boundedContext.chat.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 회원의 챗봇 대화 하나. 메시지는 ChatMessage가 conversationId로 가리킴 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_conversation")
public class ChatConversation extends BaseIdAndTime {

    private static final int TITLE_MAX_LENGTH = 100;

    // 마지막 메시지 시각. 대화 목록 정렬에 씀
    @Column(nullable = false)
    private LocalDateTime lastMessageAt;

    @Column(nullable = false)
    private Long memberId;

    // 첫 질문(100자까지)
    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    public static ChatConversation start(Long memberId, String firstQuestion, LocalDateTime now) {
        ChatConversation conversation = new ChatConversation();
        conversation.memberId = memberId;
        conversation.title = firstQuestion.length() > TITLE_MAX_LENGTH ? firstQuestion.substring(0, TITLE_MAX_LENGTH) : firstQuestion;
        conversation.lastMessageAt = now;
        return conversation;
    }

    public void touch(LocalDateTime now) {
        this.lastMessageAt = now;
    }
}
