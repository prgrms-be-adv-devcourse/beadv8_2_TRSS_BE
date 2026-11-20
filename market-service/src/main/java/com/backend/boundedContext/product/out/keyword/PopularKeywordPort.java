package com.backend.boundedContext.product.out.keyword;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 인기 검색어 저장소. 검색어를 시간 단위로 세고, 여러 시간의 합계로 순위를 낸다.
 * 지금은 Redis 구현({@link PopularKeywordRedisAdapter})이고, 검색 컨텍스트를 따로 빼거나 저장소를 바꿀 때 구현만 갈아 끼운다.
 */
public interface PopularKeywordPort {

    /**
     * 검색어를 at이 속한 시간에 한 번 센다. key로 세고(띄어쓰기만 다른 검색어는 같은 key), 순위에는 label을 보여 준다.
     * 같은 key의 label은 마지막으로 센 검색의 것으로 바뀐다.
     * 검색 응답을 늦추지 않도록 기다리지 않고, 실패해도 예외를 던지지 않는다.
     */
    void increase(String key, String label, LocalDateTime at);

    /**
     * lastHour가 속한 시간부터 거꾸로 hours개 시간의 감쇠 점수가 큰 순서로 검색어(label)를 limit개까지 돌려준다.
     * 감쇠 점수는 시간마다 횟수에 2^(-지난 시간/halfLifeHours)를 곱해 더한 값이다 (lastHour가 속한 시간은 1, 반감기만큼 전은 0.5).
     * 점수가 minScore보다 작은 검색어는 뺀다. 점수가 같으면 key 순서이고, label이 없으면 key를 그대로 보여 준다.
     */
    List<String> findTop(LocalDateTime lastHour, int hours, double halfLifeHours, double minScore, int limit);
}
