package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.ProductJpaSearchAdapter;
import com.backend.boundedContext.product.out.ProductSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ES 장애 직후 건너뛰기. 시간은 테스트 Clock으로 돌리고, ES 클라이언트는 mock이라 ES 없이 돈다.
 */
class ProductEsSearchGuardTest {
    private static final Duration SKIP = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private final ProductEsIndexAdapter indexAdapter = mock(ProductEsIndexAdapter.class);
    private ProductEsSearchGuard guard;

    @BeforeEach
    void setUp() {
        given(indexAdapter.isKnownCurrent()).willReturn(true);
        guard = new ProductEsSearchGuard(indexAdapter, SKIP, clock);
    }

    @Test
    @DisplayName("접속·응답 실패 뒤 30초 동안은 ES를 건너뛰고, 30초가 지나면 다시 ES를 부른다")
    void skipAfterConnectionFailure() {
        assertThat(guard.canSearch()).isTrue();

        guard.onFailure(new ConnectException("Connection refused"));
        assertThat(guard.canSearch()).isFalse();

        clock.advance(SKIP.minusSeconds(1));
        assertThat(guard.canSearch()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(guard.canSearch()).isTrue();
    }

    @Test
    @DisplayName("원인 사슬에 IOException이 있으면(응답 시간 초과를 감싼 예외) 접속·응답 실패로 본다")
    void wrappedTimeoutIsUnavailable() {
        guard.onFailure(new RuntimeException("search failed", new SocketTimeoutException("3,000 MILLISECONDS")));

        assertThat(guard.canSearch()).isFalse();
    }

    @Test
    @DisplayName("질의 오류(ES가 응답한 실패)는 건너뛰지 않는다")
    void queryErrorDoesNotSkip() {
        guard.onFailure(new SearchIndexUnavailableException("ES 질의가 실패했습니다. parse error"));
        guard.onFailure(new IllegalArgumentException("bad query"));

        assertThat(guard.canSearch()).isTrue();
    }

    @Test
    @DisplayName("건너뛰기가 끝난 뒤 다시 성공하면 건너뛰기를 끝내고, 또 실패하면 그 시각부터 다시 30초 건너뛴다")
    void recoverOrSkipAgain() {
        guard.onFailure(new ConnectException("Connection refused"));
        clock.advance(SKIP);
        guard.onSuccess();
        assertThat(guard.canSearch()).isTrue();

        guard.onFailure(new ConnectException("Connection refused"));
        clock.advance(SKIP);
        guard.onFailure(new ConnectException("Connection refused"));   // 다시 불러 봤지만 아직 실패
        clock.advance(SKIP.minusSeconds(1));
        assertThat(guard.canSearch()).isFalse();
    }

    @Test
    @DisplayName("인덱스가 최신이 아니면(매핑 버전) 장애와 관계없이 ES를 쓰지 않는다")
    void notCurrentIndex() {
        given(indexAdapter.isKnownCurrent()).willReturn(false);

        assertThat(guard.canSearch()).isFalse();
    }

    @Test
    @DisplayName("검색 어댑터: ES 접속이 실패하면 DB로 대신하고, 건너뛰는 동안에는 ES를 부르지 않고 바로 DB로 대신한다")
    @SuppressWarnings("unchecked")
    void searchAdapterSkipsEsWhileDown() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        given(client.search(any(Function.class), eq(Void.class))).willThrow(new ConnectException("Connection refused"));
        ProductJpaSearchAdapter fallback = mock(ProductJpaSearchAdapter.class);
        Page<Long> fromDb = new PageImpl<>(List.of(7L));
        given(fallback.searchProductIds(any(), any())).willReturn(fromDb);
        ProductEsSearchAdapter adapter = new ProductEsSearchAdapter(client, fallback, guard, "products");
        ProductSearchQuery query = new ProductSearchQuery("크림", List.of(10L), true, false, ProductSort.RELEVANCE);

        assertThat(adapter.searchProductIds(query, PageRequest.of(0, 20))).isSameAs(fromDb);   // ES를 불렀다가 실패
        assertThat(adapter.searchProductIds(query, PageRequest.of(0, 20))).isSameAs(fromDb);   // 건너뛴다

        verify(client, times(1)).search(any(Function.class), eq(Void.class));
        verify(fallback, times(2)).searchProductIds(any(), any());
    }

    /** 테스트가 시각을 앞으로 돌리는 Clock */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
