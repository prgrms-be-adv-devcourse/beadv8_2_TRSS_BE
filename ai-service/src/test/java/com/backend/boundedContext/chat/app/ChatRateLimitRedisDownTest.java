package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.out.ChatRateLimitStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * Redis가 응답하지 않으면 요청 수를 셀 수 없어도 챗봇을 막지 않음
 */
@SpringBootTest
class ChatRateLimitRedisDownTest {

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ChatRateLimitStore chatRateLimitStore;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_conversation");
    }

    @Test
    @DisplayName("Redis 장애 때는 요청 제한 없이 답함")
    void failOpen() {
        given(chatRateLimitStore.increase(anyLong(), any(Duration.class))).willThrow(new RedisConnectionFailureException("가짜 Redis 장애"));

        assertThat(chatFacade.answer(31L, null, "질문").answerType()).isEqualTo(ChatAnswerType.NO_MATCH);
    }
}
