package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.app.dto.ChatConversationDto;
import com.backend.boundedContext.chat.app.dto.ChatMessageDto;
import com.backend.boundedContext.chat.domain.ChatConversation;
import com.backend.boundedContext.chat.domain.ChatErrorCode;
import com.backend.boundedContext.chat.domain.ChatMessage;
import com.backend.boundedContext.chat.out.ChatConversationRepository;
import com.backend.boundedContext.chat.out.ChatMessageRepository;
import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// 내 대화 목록·메시지 조회. 다른 회원의 대화는 없는 대화와 같게 404
@Service
@RequiredArgsConstructor
public class ChatGetConversationsUseCase {

    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final FaqFacade faqFacade;

    // 최근 대화 순. 정렬은 고정이라 요청의 sort는 쓰지 않음
    public PageResponse<ChatConversationDto> getConversations(Long memberId, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return PageResponse.from(chatConversationRepository.findByMemberIdOrderByLastMessageAtDescIdDesc(memberId, page), ChatConversationDto::from);
    }

    // 대화의 메시지를 오래된 순으로 모두. 근거 FAQ는 지금 FAQ의 카테고리·질문으로 보여 줌
    public List<ChatMessageDto> getMessages(Long memberId, Long conversationId) {
        ChatConversation conversation = chatConversationRepository.findByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new DomainException(ChatErrorCode.CONVERSATION_NOT_FOUND));
        List<ChatMessage> messages = chatMessageRepository.findByConversationIdOrderByIdAsc(conversation.getId());
        Map<Long, FaqSummaryDto> faqs = faqFacade.getSummaries(messages.stream().flatMap(m -> m.getSourceFaqIds().stream()).distinct().toList());

        return messages.stream()
                .map(m -> new ChatMessageDto(m.getId(), m.getRole(), m.getContent(), m.getAnswerType(),
                        m.getSourceFaqIds().stream().map(faqs::get).filter(Objects::nonNull).toList(), m.getCreatedAt()))
                .toList();
    }
}
