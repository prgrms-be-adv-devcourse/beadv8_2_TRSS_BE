package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.domain.ChatMessage;
import com.backend.boundedContext.chat.domain.ChatPrompt;
import com.backend.boundedContext.faq.app.FaqFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FAQ 검색 전에 질문을 FAQ 질문 같은 문장으로 다시 씀("돈 넣은 거 돌려받고 싶어요" → "예치금 환불은 어떻게 하나요?").
 * 이전 대화로 생략된 대상도 채움("거절당하면요?" → "반품이 거절되면 어떻게 되나요?").
 * 노출 중인 FAQ 질문 목록을 함께 줘서 FAQ 표현에 맞춰 바꾸게 함. 같은 질문이면 같은 검색어가 나오게 온도는 0
 * 실측(바꿔 말한 질문 24개)에서 정답 FAQ가 근거에 드는 수가 다시 쓰기 전 11개(기준값 0.4)에서 24개로 늘어 넣음
 * OpenAI를 부르므로 트랜잭션 밖에서 부름
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueryRewriter {

    // 다시 쓴 문장이 이보다 길면 지시를 따르지 않은 것으로 보고 버림(질문 최대 길이와 같음)
    private static final int MAX_LENGTH = 200;

    private final ChatModel chatModel;
    private final FaqFacade faqFacade;

    // 다시 쓴 검색어. 실패·시간 초과·빈 답·너무 긴 답이면 null(호출한 쪽이 원래 질문으로 검색)
    public String rewrite(List<ChatMessage> history, String question) {
        try {
            List<Message> messages = List.of(new SystemMessage(ChatPrompt.rewriteSystem(faqFacade.getVisibleQuestions())), new UserMessage(ChatPrompt.rewriteInput(history, question)));
            Prompt prompt = chatModel instanceof OpenAiChatModel openAi ? new Prompt(messages, temperatureZero(openAi.getOptions())) : new Prompt(messages);   // 테스트의 가짜 모델은 옵션 없이
            String text = chatModel.call(prompt).getResult().getOutput().getText();
            String line = text == null ? "" : text.strip().lines().findFirst().orElse("").strip();

            if (line.isEmpty() || line.length() > MAX_LENGTH) {
                log.warn("chat query rewrite unusable length={}", line.length());
                return null;
            }
            return line;
        } catch (RuntimeException e) {
            log.warn("chat query rewrite failed reason={}", e.toString());
            return null;
        }
    }

    // 같은 질문이면 같은 검색어가 나오게 온도만 0으로 바꿈(답변 생성은 0.2). 모델 등은 기본 설정을 복사해 그대로 씀
    // 새 옵션만 넘기면 기본 설정과 합쳐지지 않아 모델이 SDK 기본값으로 바뀌고, 범용 ChatOptions는 OpenAI 모델이 받지 않음(실측)
    private static OpenAiChatOptions temperatureZero(OpenAiChatOptions defaults) {
        return defaults.mutate().temperature(0.0).build();
    }
}
