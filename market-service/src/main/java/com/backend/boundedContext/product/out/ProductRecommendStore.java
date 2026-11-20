package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.MemberRecommendation;

import java.time.Duration;
import java.util.Optional;

/** 회원별로 미리 계산한 추천 저장소 */
public interface ProductRecommendStore {

    /**
     * 저장한 추천. 없거나 읽을 수 없는 값(형식이 바뀐 예전 값 등)이면 빈 값.
     * 저장소가 응답하지 않으면 {@link RecommendStoreUnavailableException}
     */
    Optional<MemberRecommendation> find(Long memberId);

    /** 저장소가 응답하지 않으면 로그만 남긴다 (다음 계산 때 다시 저장한다) */
    void save(Long memberId, MemberRecommendation recommendation, Duration ttl);
}
