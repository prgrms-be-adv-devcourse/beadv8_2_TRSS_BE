package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.PopularKeywordDto;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.out.keyword.PopularKeywordPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 인기 검색어: 어떤 검색을 세는지(정규화·조건)와 순위 조회(순위 변동·캐시·실패)를 가짜 저장소로 확인한다.
 * 집계 구간 24시간, 반감기 3시간, 최소 점수 1, 10개, 최대 30자, 캐시 60초.
 */
class ProductPopularKeywordTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 14, 30);

    private final FakePort port = new FakePort();
    private final ProductPolicy policy = new ProductPolicy(10, 500, 30, 5, 24, 3.0, 1.0, 10, 30, 60);
    private final ProductPopularKeywordSupport support = new ProductPopularKeywordSupport(port, policy);
    private final ProductGetPopularKeywordsUseCase useCase = new ProductGetPopularKeywordsUseCase(port, policy);

    @Test
    @DisplayName("기록: 띄어쓰기·대소문자만 다른 검색어는 띄어쓰기 없앤 소문자 key로 세고, 이름은 공백만 정리한 소문자다")
    void recordNormalizes() {
        support.recordSearch("  수분   크림 ", 0, 3, NOW);
        support.recordSearch("수분크림", 0, 3, NOW);
        support.recordSearch("COSRX 토너", 0, 1, NOW);

        assertThat(port.increased).containsExactly("수분크림=수분 크림", "수분크림=수분크림", "cosrx토너=cosrx 토너");
    }

    @Test
    @DisplayName("기록: 검색어가 없거나, 다음 페이지이거나, 결과가 0건이거나, 30자를 넘으면 세지 않는다")
    void recordSkips() {
        support.recordSearch(null, 0, 5, NOW);
        support.recordSearch("   ", 0, 5, NOW);
        support.recordSearch("수분크림", 1, 5, NOW);
        support.recordSearch("수분크림", 0, 0, NOW);
        support.recordSearch("가".repeat(31), 0, 5, NOW);
        support.recordSearch("가".repeat(30), 0, 5, NOW);

        assertThat(port.increased).containsExactly("가".repeat(30) + "=" + "가".repeat(30));
    }

    @Test
    @DisplayName("조회: 지금 순위에 한 시간 전 순위와의 차이를 붙이고, 한 시간 전에 없던 검색어는 null이다")
    void rankChange() {
        port.top.put(NOW, List.of("선크림", "수분크림", "토너", "앰플"));
        port.top.put(NOW.minusHours(1), List.of("수분크림", "토너", "선크림", "클렌징폼"));

        assertThat(useCase.getPopularKeywords(NOW))
                .extracting(PopularKeywordDto::rank, PopularKeywordDto::keyword, PopularKeywordDto::rankChange)
                .containsExactly(
                        tuple(1, "선크림", 2),
                        tuple(2, "수분크림", -1),
                        tuple(3, "토너", -1),
                        tuple(4, "앰플", null));
        assertThat(port.findCalls).containsExactly(NOW, NOW.minusHours(1));
    }

    @Test
    @DisplayName("조회: 60초 동안은 합친 결과를 다시 쓰고, 지나면 다시 읽는다")
    void cache() {
        port.top.put(NOW, List.of("선크림"));
        useCase.getPopularKeywords(NOW);

        port.top.put(NOW.plusSeconds(59), List.of("토너"));
        assertThat(useCase.getPopularKeywords(NOW.plusSeconds(59))).extracting(PopularKeywordDto::keyword).containsExactly("선크림");

        port.top.put(NOW.plusSeconds(60), List.of("토너"));
        assertThat(useCase.getPopularKeywords(NOW.plusSeconds(60))).extracting(PopularKeywordDto::keyword).containsExactly("토너");
    }

    @Test
    @DisplayName("조회: 저장소가 실패하면 빈 목록이고, 캐시 동안은 다시 부르지 않는다")
    void failureReturnsEmpty() {
        port.fail = true;

        assertThat(useCase.getPopularKeywords(NOW)).isEmpty();
        assertThat(useCase.getPopularKeywords(NOW.plusSeconds(30))).isEmpty();
        assertThat(port.findCalls).hasSize(1);
    }

    /** 기록한 검색어를 "key=이름"으로 모으고, 기준 시각별로 정해 둔 순위를 돌려주는 저장소 */
    private static class FakePort implements PopularKeywordPort {
        final List<String> increased = new ArrayList<>();
        final Map<LocalDateTime, List<String>> top = new HashMap<>();
        final List<LocalDateTime> findCalls = new ArrayList<>();
        boolean fail;

        @Override
        public void increase(String key, String label, LocalDateTime at) {
            increased.add(key + "=" + label);
        }

        @Override
        public List<String> findTop(LocalDateTime lastHour, int hours, double halfLifeHours, double minScore, int limit) {
            findCalls.add(lastHour);
            if (fail) throw new IllegalStateException("redis down");
            assertThat(hours).isEqualTo(24);
            assertThat(halfLifeHours).isEqualTo(3.0);
            assertThat(minScore).isEqualTo(1.0);
            assertThat(limit).isEqualTo(10);
            return top.getOrDefault(lastHour, List.of());
        }
    }
}
