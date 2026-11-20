package com.backend.boundedContext.chat.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 챗봇 컨텍스트 정책 값 (custom.chat.*)
@Component
@Getter
public class ChatPolicy {

    // 프롬프트에 넣는 같은 대화의 최근 메시지 수(질문·답변 합산)
    private final int historyMessages;

    // 검색어 다시 쓰기가 실패했을 때 FAQ 검색어 앞에 붙이는 같은 대화의 최근 질문 수. "그럼 며칠 걸려요?"처럼 주어가 빠진 질문도 맞는 FAQ를 찾게
    private final int searchHistoryQuestions;

    // 회원별로 rateLimitWindow 동안 보낼 수 있는 챗봇 질문 수. 질문마다 OpenAI를 부르므로 비용을 막으려고 둠
    private final int rateLimitCount;

    // 요청 수를 세는 시간. 첫 질문부터 이 시간이 지나면 다시 0부터 셈
    private final Duration rateLimitWindow;

    public ChatPolicy(@Value("${custom.chat.historyMessages}") int historyMessages,
                      @Value("${custom.chat.searchHistoryQuestions}") int searchHistoryQuestions,
                      @Value("${custom.chat.rateLimitCount}") int rateLimitCount,
                      @Value("${custom.chat.rateLimitWindow}") Duration rateLimitWindow) {
        if (historyMessages < 0) throw new IllegalArgumentException("custom.chat.historyMessages는 0 이상이어야 합니다: " + historyMessages);
        if (searchHistoryQuestions < 0) throw new IllegalArgumentException("custom.chat.searchHistoryQuestions는 0 이상이어야 합니다: " + searchHistoryQuestions);
        if (rateLimitCount < 1) throw new IllegalArgumentException("custom.chat.rateLimitCount는 1 이상이어야 합니다: " + rateLimitCount);
        if (rateLimitWindow.isNegative() || rateLimitWindow.isZero()) throw new IllegalArgumentException("custom.chat.rateLimitWindow는 0보다 커야 합니다: " + rateLimitWindow);
        this.historyMessages = historyMessages;
        this.searchHistoryQuestions = searchHistoryQuestions;
        this.rateLimitCount = rateLimitCount;
        this.rateLimitWindow = rateLimitWindow;
    }
}
