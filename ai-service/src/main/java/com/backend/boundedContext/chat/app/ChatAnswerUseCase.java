package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.domain.ChatCitation;
import com.backend.boundedContext.chat.domain.ChatConversation;
import com.backend.boundedContext.chat.domain.ChatErrorCode;
import com.backend.boundedContext.chat.domain.ChatMessage;
import com.backend.boundedContext.chat.domain.ChatPolicy;
import com.backend.boundedContext.chat.domain.ChatPrompt;
import com.backend.boundedContext.chat.domain.ChatRole;
import com.backend.boundedContext.chat.out.ChatConversationRepository;
import com.backend.boundedContext.chat.out.ChatMessageRepository;
import com.backend.boundedContext.chat.out.ChatRateLimitStore;
import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 질문과 의미가 가까운 FAQ를 찾아, 그 FAQ만 근거로 LLM이 답변을 만듦(RAG). 질문·답변은 대화 기록에 저장함
 * - 검색 전에 질문을 FAQ 질문 같은 문장으로 다시 씀(이전 대화로 생략된 대상도 채움). 실패하면 같은 대화의 최근 질문 + 이번 질문으로 검색함
 * - 최근 메시지를 답변 프롬프트에 넣어 이어지는 질문에 답함
 * - 근거 FAQ가 없으면 LLM을 부르지 않고 고객센터 안내 문구로 답함
 * - LLM이 실패하거나 시간을 넘기면(spring.ai.openai.chat.timeout) 가장 비슷한 FAQ 답변을 그대로 줌
 * - 근거 FAQ는 LLM이 답변 끝에 적은 번호의 FAQ만 줌. FAQ로 답할 수 없다고 하면 근거 없이 NO_MATCH
 * OpenAI 호출 동안 DB 연결을 잡지 않게, 대화 읽기와 저장만 짧은 트랜잭션으로 나눔
 * 회원별 요청 횟수를 넘으면 OpenAI를 부르기 전에 429로 막음(custom.chat.rateLimit*)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnswerUseCase {

    private final FaqFacade faqFacade;
    private final ChatModel chatModel;
    private final ChatPolicy chatPolicy;
    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate transactionTemplate;
    private final ChatRateLimitStore chatRateLimitStore;
    private final ChatQueryRewriter chatQueryRewriter;

    // conversationId가 null이면 새 대화를 시작함. 다른 회원의 대화면 404
    public ChatAnswerDto answer(Long memberId, Long conversationId, String question) {
        checkRateLimit(memberId);

        // 1. 짧은 읽기: 내 대화인지 확인하고 최근 메시지를 가져옴(오래된 순)
        List<ChatMessage> history = conversationId == null ? List.of() : readHistory(memberId, conversationId);

        // 2. 트랜잭션 밖에서 검색어 다시 쓰기·FAQ 검색·답변 생성
        String rewritten = chatQueryRewriter.rewrite(history, question);
        List<FaqSearchDto.Item> faqs = faqFacade.search(rewritten != null ? rewritten : searchText(history, question)).faqs();
        ChatAnswerType answerType;
        String answer;
        List<FaqSearchDto.Item> sourceFaqs;

        if (faqs.isEmpty()) {
            answerType = ChatAnswerType.NO_MATCH;
            answer = ChatPrompt.NO_MATCH_ANSWER;
            sourceFaqs = List.of();
        } else {
            ChatCitation generated = generate(history, question, faqs);
            if (generated == null) {
                // LLM 실패: 가장 비슷한 FAQ 답변을 그대로 주므로 근거도 그 FAQ 하나
                answerType = ChatAnswerType.FAQ;
                answer = faqs.getFirst().answer();
                sourceFaqs = List.of(faqs.getFirst());
            } else if (generated.faqNumbers() != null && generated.faqNumbers().isEmpty()) {
                // 찾은 FAQ로도 답할 수 없다고 함: 근거 칩을 붙이면 그 FAQ에 답이 있다고 오해하므로 근거 없이
                answerType = ChatAnswerType.NO_MATCH;
                answer = generated.answer();
                sourceFaqs = List.of();
            } else {
                answerType = ChatAnswerType.AI;
                answer = generated.answer();
                sourceFaqs = citedFaqs(faqs, generated.faqNumbers());
            }
        }

        // 3. 짧은 저장: 질문·답변을 대화에 남김
        List<Long> sourceFaqIds = sourceFaqs.stream().map(FaqSearchDto.Item::faqId).toList();
        Long savedConversationId = transactionTemplate.execute(tx -> save(memberId, conversationId, question, answer, answerType, sourceFaqIds));

        List<FaqSummaryDto> sources = sourceFaqs.stream()
                .map(faq -> new FaqSummaryDto(faq.faqId(), faq.category(), faq.categoryLabel(), faq.question()))
                .toList();

        return new ChatAnswerDto(savedConversationId, answer, answerType, sources);
    }

    // 요청 횟수를 올리고, 넘었으면 429. Redis 장애 때는 챗봇을 막지 않고 통과시킴(비용 보호보다 상담 가능을 우선)
    private void checkRateLimit(Long memberId) {
        long count;
        try {
            count = chatRateLimitStore.increase(memberId, chatPolicy.getRateLimitWindow());
        } catch (RuntimeException e) {
            log.warn("chat rate limit check failed memberId={} reason={}", memberId, e.toString());
            return;
        }

        if (count > chatPolicy.getRateLimitCount()) throw new DomainException(GlobalErrorCode.TOO_MANY_REQUESTS);
    }

    private List<ChatMessage> readHistory(Long memberId, Long conversationId) {
        return transactionTemplate.execute(tx -> {
            findMine(memberId, conversationId);
            List<ChatMessage> recent = new ArrayList<>(chatMessageRepository.findByConversationIdOrderByIdDesc(conversationId, Limit.of(chatPolicy.getHistoryMessages())));
            return recent.reversed();
        });
    }

    // 다시 쓰기가 실패했을 때의 FAQ 검색어 = 같은 대화의 최근 질문 + 이번 질문. "그럼 며칠 걸려요?"만으로는 맞는 FAQ가 안 나와서 앞 질문을 붙임
    private String searchText(List<ChatMessage> history, String question) {
        List<String> previous = history.stream().filter(m -> m.getRole() == ChatRole.USER).map(ChatMessage::getContent).toList();
        List<String> parts = new ArrayList<>(previous.subList(Math.max(0, previous.size() - chatPolicy.getSearchHistoryQuestions()), previous.size()));
        parts.add(question);
        return String.join("\n", parts);
    }

    // LLM이 근거로 쓴 FAQ(검색 순서). 근거 표시가 없거나 읽을 수 없으면(null) 찾은 FAQ 전부
    private static List<FaqSearchDto.Item> citedFaqs(List<FaqSearchDto.Item> faqs, List<Integer> numbers) {
        if (numbers == null) return faqs;

        List<FaqSearchDto.Item> cited = new ArrayList<>();
        for (int i = 0; i < faqs.size(); i++) {
            if (numbers.contains(i + 1)) cited.add(faqs.get(i));
        }
        return cited;
    }

    // LLM 답변과 근거 표시. 실패·시간 초과·빈 답변이면 null
    private ChatCitation generate(List<ChatMessage> history, String question, List<FaqSearchDto.Item> faqs) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ChatPrompt.system(faqs)));

        for (ChatMessage m : history) {
            messages.add(m.getRole() == ChatRole.USER ? new UserMessage(m.getContent()) : new AssistantMessage(m.getContent()));
        }
        messages.add(new UserMessage(question));

        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            String text = response.getResult().getOutput().getText();

            ChatCitation citation = text == null ? null : ChatCitation.parse(text, faqs.size());

            if (citation == null || citation.answer().isBlank()) {
                log.warn("chat answer empty faqIds={}", faqs.stream().map(FaqSearchDto.Item::faqId).toList());
                return null;
            }
            if (citation.faqNumbers() == null) log.warn("chat answer citation unreadable, using all faqs faqIds={}", faqs.stream().map(FaqSearchDto.Item::faqId).toList());
            return citation;
        } catch (RuntimeException e) {
            log.warn("chat answer failed reason={}", e.toString());
            return null;
        }
    }

    private Long save(Long memberId, Long conversationId, String question, String answer, ChatAnswerType answerType, List<Long> sourceFaqIds) {
        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation;

        if (conversationId == null) {
            conversation = chatConversationRepository.save(ChatConversation.start(memberId, question, now));
        } else {
            conversation = findMine(memberId, conversationId);
            conversation.touch(now);
        }

        chatMessageRepository.save(ChatMessage.question(conversation.getId(), question));
        chatMessageRepository.save(ChatMessage.answer(conversation.getId(), answer, answerType, sourceFaqIds));
        return conversation.getId();
    }

    private ChatConversation findMine(Long memberId, Long conversationId) {
        return chatConversationRepository.findByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new DomainException(ChatErrorCode.CONVERSATION_NOT_FOUND));
    }
}
