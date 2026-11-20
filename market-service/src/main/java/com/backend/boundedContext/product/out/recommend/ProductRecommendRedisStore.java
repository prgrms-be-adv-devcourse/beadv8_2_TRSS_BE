package com.backend.boundedContext.product.out.recommend;

import com.backend.boundedContext.product.domain.MemberRecommendation;
import com.backend.boundedContext.product.out.ProductRecommendStore;
import com.backend.boundedContext.product.out.RecommendStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * 회원 추천을 Redis에 JSON 문자열로 둔다 (키 {@code product:recommend:member:{memberId}}, TTL).
 * 오래 활동이 없는 회원의 추천은 TTL로 사라지고, 다음 요청 때 다시 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRecommendRedisStore implements ProductRecommendStore {
    /** 테스트에서 키를 직접 확인하거나 지울 때도 이 접두어를 쓴다 */
    public static final String KEY_PREFIX = "product:recommend:member:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<MemberRecommendation> find(Long memberId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(KEY_PREFIX + memberId);
        } catch (DataAccessException e) {
            throw new RecommendStoreUnavailableException("추천 저장소가 응답하지 않습니다. memberId=" + memberId, e);
        }
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, MemberRecommendation.class));
        } catch (JacksonException e) {
            log.warn("저장한 추천을 읽지 못해 없는 것으로 봅니다(다시 계산해 덮어쓴다). memberId={}", memberId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(Long memberId, MemberRecommendation recommendation, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + memberId, objectMapper.writeValueAsString(recommendation), ttl);
        } catch (RuntimeException e) {
            log.warn("추천을 저장하지 못했습니다. 다음 요청 때 다시 계산합니다. memberId={}", memberId, e);
        }
    }
}
