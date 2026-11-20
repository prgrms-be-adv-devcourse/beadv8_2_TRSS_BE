package com.backend.boundedContext.chat.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/** 대화의 질문·답변 메시지 하나 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_message")
public class ChatMessage extends BaseIdAndTime {

    @Column(nullable = false)
    private Long conversationId;

    // 답변을 만든 방식. 질문 메시지는 null
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ChatAnswerType answerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 답변 근거 FAQ ID(유사도 높은 순). 질문 메시지는 빈 배열
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "bigint[]")
    private Long[] sourceFaqIds;

    public static ChatMessage question(Long conversationId, String content) {
        return of(conversationId, ChatRole.USER, content, null, List.of());
    }

    public static ChatMessage answer(Long conversationId, String content, ChatAnswerType answerType, List<Long> sourceFaqIds) {
        return of(conversationId, ChatRole.ASSISTANT, content, answerType, sourceFaqIds);
    }

    public List<Long> getSourceFaqIds() {
        return List.of(sourceFaqIds);
    }

    private static ChatMessage of(Long conversationId, ChatRole role, String content, ChatAnswerType answerType, List<Long> sourceFaqIds) {
        ChatMessage message = new ChatMessage();
        message.conversationId = conversationId;
        message.role = role;
        message.content = content;
        message.answerType = answerType;
        message.sourceFaqIds = sourceFaqIds.toArray(Long[]::new);
        return message;
    }
}
