package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.MemberRecommendation;
import com.backend.boundedContext.product.domain.ProductRecommendPolicy;
import com.backend.boundedContext.product.app.dto.MemberRecommendationsDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.RecommendBasis;
import com.backend.boundedContext.product.out.ProductRecommendStore;
import com.backend.boundedContext.product.out.RecommendStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 저장한 추천 읽기: 없으면 그 자리에서 계산, 오래됐으면 돌려준 뒤 뒤에서 다시 계산, 새것이면 그대로 */
class ProductGetMemberRecommendationsUseCaseTest {

    private static final long MEMBER_ID = 7L;

    private final ProductRecommendSupport support = mock(ProductRecommendSupport.class);
    private final ProductRecommendRefresher refresher = mock(ProductRecommendRefresher.class);
    private final ProductRecommendStore store = mock(ProductRecommendStore.class);
    private final ProductRecommendPolicy policy = new ProductRecommendPolicy(1.2, 1.0, 0.7, 0.5, 4, 2, 30, 180, 5,
            0.35, 0.2, 0.15, 0.3, 30, 50, 3, 50, 3, 20, 60, 7, 20);
    private final ProductGetMemberRecommendationsUseCase useCase =
            new ProductGetMemberRecommendationsUseCase(policy, support, refresher, store);

    @BeforeEach
    void setUp() {
        given(support.summaries(any())).willReturn(Map.of());
        given(support.productNames(any())).willReturn(Map.of());
        given(support.thumbnailUrls(any())).willReturn(Map.of());
        given(support.pick(any(), any(), anyInt(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("저장한 결과가 없으면 그 자리에서 계산한다")
    void computeWhenMissing() {
        given(store.find(MEMBER_ID)).willReturn(Optional.empty());
        given(support.compute(MEMBER_ID)).willReturn(stored(LocalDateTime.now()));

        useCase.getRecommendations(MEMBER_ID, 10);

        verify(support).compute(MEMBER_ID);
        verify(refresher, never()).refreshLater(any());
    }

    @Test
    @DisplayName("저장한 결과가 60분보다 오래됐으면 그대로 쓰고 뒤에서 다시 계산한다")
    void refreshWhenStale() {
        given(store.find(MEMBER_ID)).willReturn(Optional.of(stored(LocalDateTime.now().minusMinutes(61))));

        useCase.getRecommendations(MEMBER_ID, 10);

        verify(refresher).refreshLater(MEMBER_ID);
        verify(support, never()).compute(any());
    }

    @Test
    @DisplayName("새 결과면 다시 계산하지 않는다")
    void useFresh() {
        given(store.find(MEMBER_ID)).willReturn(Optional.of(stored(LocalDateTime.now().minusMinutes(5))));

        useCase.getRecommendations(MEMBER_ID, 10);

        verify(refresher, never()).refreshLater(any());
        verify(support, never()).compute(any());
    }

    @Test
    @DisplayName("저장소가 응답하지 않으면 계산하지 않고 인기순으로 대신한다 (요청마다 전체 계산을 막는다)")
    void popularWhenStoreUnavailable() {
        given(store.find(MEMBER_ID)).willThrow(new RecommendStoreUnavailableException("Redis 응답 없음", null));
        given(support.popular(anyInt())).willReturn(List.of());
        given(support.pickInChunks(any(), anyInt(), any())).willReturn(List.of());

        MemberRecommendationsDto result = useCase.getRecommendations(MEMBER_ID, 10);

        assertThat(result.personalized()).isFalse();
        verify(support, never()).compute(any());
        verify(refresher, never()).refreshLater(any());
    }

    @Test
    @DisplayName("기준 상품 섹션에 기준 상품 이름과 대표 이미지를 싣는다 (대표 이미지가 없으면 null)")
    void sectionSeedThumbnail() {
        MemberRecommendation recommendation = new MemberRecommendation(true, LocalDateTime.now(), List.of(), List.of(
                new MemberRecommendation.Section(RecommendBasis.WISH, 10L, List.of(2L)),
                new MemberRecommendation.Section(RecommendBasis.PURCHASE, 11L, List.of(3L))));
        given(store.find(MEMBER_ID)).willReturn(Optional.of(recommendation));
        given(support.pick(any(), any(), anyInt(), any())).willReturn(List.of(summary(2L)));
        given(support.productNames(any())).willReturn(Map.of(10L, "시카 리페어 크림", 11L, "수분 토너"));
        given(support.thumbnailUrls(any())).willReturn(Map.of(10L, "https://img.test/10.jpg"));

        MemberRecommendationsDto result = useCase.getRecommendations(MEMBER_ID, 10);

        assertThat(result.sections()).extracting(s -> s.seedProductName(), s -> s.seedThumbnailUrl())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("시카 리페어 크림", "https://img.test/10.jpg"),
                        org.assertj.core.groups.Tuple.tuple("수분 토너", null));
    }

    private static ProductSummaryDto summary(long productId) {
        return new ProductSummaryDto(productId, "상품" + productId, 10_000, null, null, 1L, "상점", null, false, null, 0, 0);
    }

    private static MemberRecommendation stored(LocalDateTime generatedAt) {
        return new MemberRecommendation(true, generatedAt, List.of(new MemberRecommendation.Item(1L, null, null)), List.of());
    }
}
