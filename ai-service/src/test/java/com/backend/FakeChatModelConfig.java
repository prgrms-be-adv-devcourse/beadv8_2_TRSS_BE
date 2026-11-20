package com.backend;

import com.backend.boundedContext.chat.domain.ChatPrompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트는 실제 OpenAI 채팅을 부르지 않음. 시스템 메시지로 검색어 다시 쓰기와 답변 생성을 구분함
 * - 다시 쓰기: rewriteReply를 답함. null이면 [이번 질문]을 그대로 돌려줌(검색어 = 원래 질문). rewriteFailing을 켜면 예외
 * - 답변: reply를 답하고 마지막 프롬프트를 남김. failing을 켜면 예외를 던져 OpenAI 장애·시간 초과를 흉내 냄
 */
@Configuration
public class FakeChatModelConfig {

    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    public static class FakeChatModel implements ChatModel {
        public static final String DEFAULT_REPLY = "가짜 AI 답변이에요.";
        private static final String QUESTION_MARK = "[이번 질문]\n";

        // 답변 생성
        public volatile boolean failing;
        public volatile String reply = DEFAULT_REPLY;
        public volatile Prompt lastPrompt;
        public final AtomicInteger calls = new AtomicInteger();

        // 검색어 다시 쓰기
        public volatile boolean rewriteFailing;
        public volatile String rewriteReply;
        public volatile Prompt lastRewritePrompt;
        public final AtomicInteger rewriteCalls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (prompt.getSystemMessage().getText().startsWith(ChatPrompt.REWRITE_RULES)) {
                rewriteCalls.incrementAndGet();
                lastRewritePrompt = prompt;
                if (rewriteFailing) throw new IllegalStateException("가짜 OpenAI 다시 쓰기 장애");
                String input = prompt.getUserMessage().getText();
                return response(rewriteReply != null ? rewriteReply : input.substring(input.indexOf(QUESTION_MARK) + QUESTION_MARK.length()));
            }

            calls.incrementAndGet();
            lastPrompt = prompt;
            if (failing) throw new IllegalStateException("가짜 OpenAI 채팅 장애");
            return response(reply);
        }

        // 테스트마다 처음 상태로 되돌림
        public void reset() {
            failing = false;
            reply = DEFAULT_REPLY;
            lastPrompt = null;
            calls.set(0);
            rewriteFailing = false;
            rewriteReply = null;
            lastRewritePrompt = null;
            rewriteCalls.set(0);
        }

        private static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
