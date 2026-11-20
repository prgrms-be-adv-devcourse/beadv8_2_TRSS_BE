package com.backend.boundedContext.chat.out;

import com.backend.boundedContext.chat.domain.ChatConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    // 내 대화만 찾음. 다른 회원의 대화면 빈 값
    Optional<ChatConversation> findByIdAndMemberId(Long id, Long memberId);

    // 내 대화 목록. 마지막 메시지가 최근인 대화부터, 시각이 같으면 나중에 만든 대화부터(idx_chat_conversation_member_id 순서와 같음)
    Page<ChatConversation> findByMemberIdOrderByLastMessageAtDescIdDesc(Long memberId, Pageable pageable);
}
