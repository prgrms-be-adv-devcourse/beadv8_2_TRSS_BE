package com.backend.boundedContext.chat.out;

import com.backend.boundedContext.chat.domain.ChatMessage;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 대화의 메시지 전체를 오래된 순으로(대화 메시지 조회 API). 같은 시각에 저장한 질문·답변도 질문이 먼저 옴(id 순)
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    // 최근 메시지부터 limit개(프롬프트에 넣을 이전 대화). 호출한 쪽에서 오래된 순으로 뒤집어 씀
    List<ChatMessage> findByConversationIdOrderByIdDesc(Long conversationId, Limit limit);
}
