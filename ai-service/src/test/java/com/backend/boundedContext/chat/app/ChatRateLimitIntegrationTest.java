package com.backend.boundedContext.chat.app;

import com.backend.FakeChatModelConfig.FakeChatModel;
import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.chat.out.ChatRateLimitStore;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 챗봇 회원별 요청 제한을 실제 Redis로 확인함. 정책 값은 application.yaml 그대로(1분에 10번).
 * 근거 FAQ가 없어 답변은 NO_MATCH지만, 요청 수는 답변 방식과 관계없이 셈
 */
@SpringBootTest
class ChatRateLimitIntegrationTest {

    private static final Long MEMBER_ID = 21L;
    private static final Long OTHER_MEMBER_ID = 22L;

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clear() {
        redisTemplate.delete(List.of(ChatRateLimitStore.REQUEST_COUNT_KEY + MEMBER_ID, ChatRateLimitStore.REQUEST_COUNT_KEY + OTHER_MEMBER_ID));
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_conversation");
        fakeChatModel.reset();
    }

    @Test
    @DisplayName("1분에 10번까지 답하고, 11번째는 OpenAI를 부르기 전에 429. 다른 회원은 따로 셈")
    void limit() {
        for (int i = 0; i < 10; i++) chatFacade.answer(MEMBER_ID, null, "질문 " + i);
        int embeddingCalls = fakeEmbeddingModel.calls.get();

        assertThatThrownBy(() -> chatFacade.answer(MEMBER_ID, null, "질문 10"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("429-TOO_MANY_REQUESTS"));
        assertThat(fakeEmbeddingModel.calls).hasValue(embeddingCalls);

        chatFacade.answer(OTHER_MEMBER_ID, null, "다른 회원 질문");
    }

    @Test
    @DisplayName("첫 요청 때 1분 TTL을 걸고, 이후 요청은 TTL을 늘리지 않음")
    void ttl() {
        chatFacade.answer(MEMBER_ID, null, "첫 질문");
        String key = ChatRateLimitStore.REQUEST_COUNT_KEY + MEMBER_ID;
        redisTemplate.expire(key, 30, TimeUnit.SECONDS);   // 시간이 흐른 것처럼 줄여 둠

        chatFacade.answer(MEMBER_ID, null, "두 번째 질문");

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("2");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 30L);
    }
}
