package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.ProductRecommendSeedSupport.MemberSnapshot;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.MemberRecommendation;
import com.backend.boundedContext.product.domain.ProductRecommendPolicy;
import com.backend.boundedContext.product.domain.ProductSimilarPolicy;
import com.backend.boundedContext.product.domain.RecommendBasis;
import com.backend.boundedContext.product.domain.RecommendCandidate;
import com.backend.boundedContext.product.domain.RecommendCandidates;
import com.backend.boundedContext.product.domain.RecommendSeed;
import com.backend.boundedContext.product.domain.RecommendSeedProduct;
import com.backend.boundedContext.product.out.ProductRecommendPort;
import com.backend.boundedContext.product.out.ProductRecommendQuery;
import com.backend.boundedContext.product.out.ProductRecommendStore;
import com.backend.boundedContext.product.out.ProductSearchPort;
import com.backend.boundedContext.product.out.ProductSimilarPort;
import com.backend.boundedContext.product.out.RecommendStoreUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 추천 계산: ES 대신 DB 후보로 계산한 결과는 저장하지 않고, 후보는 걸러진 만큼 다음 묶음에서 채운다 */
class ProductRecommendSupportTest {

    private static final long MEMBER_ID = 7L;

    private final ProductRecommendPolicy policy = new ProductRecommendPolicy(1.2, 1.0, 0.7, 0.5, 4, 2, 30, 180, 5,
            0.35, 0.2, 0.15, 0.3, 30, 50, 3, 50, 3, 20, 60, 7, 20);
    private final ProductRecommendSeedSupport seedSupport = mock(ProductRecommendSeedSupport.class);
    private final ProductSummarySupport summarySupport = mock(ProductSummarySupport.class);
    private final ProductRecommendPort recommendPort = mock(ProductRecommendPort.class);
    private final ProductSimilarPort similarPort = mock(ProductSimilarPort.class);
    private final ProductRecommendStore store = mock(ProductRecommendStore.class);
    private final ProductRecommendSupport support = new ProductRecommendSupport(policy,
            new ProductSimilarPolicy(0.35, 0.2, 0.15, 0.3, 50), seedSupport, summarySupport,
            mock(ProductImageSupport.class), recommendPort, similarPort,
            mock(ProductSearchPort.class), store);

    @Test
    @DisplayName("ES를 쓸 수 없어 DB 후보로 계산했으면 저장하지 않고 이전 결과를 돌려준다 (이전 결과가 없으면 이번 결과를 저장 없이)")
    void degradedNotSaved() {
        givenSnapshot();
        given(recommendPort.findCandidates(any())).willReturn(RecommendCandidates.withoutText(List.of(
                new RecommendCandidate(20L, 10L, 10_000, 0, Map.of()))).asDegraded());
        MemberRecommendation previous = new MemberRecommendation(true, LocalDateTime.now().minusHours(3),
                List.of(new MemberRecommendation.Item(99L, null, null)), List.of());
        given(store.find(MEMBER_ID)).willReturn(Optional.of(previous));

        assertThat(support.compute(MEMBER_ID)).isSameAs(previous);
        verify(store, never()).save(any(), any(), any());

        given(store.find(MEMBER_ID)).willThrow(new RecommendStoreUnavailableException("down", null));
        assertThat(support.compute(MEMBER_ID).forYou()).extracting(MemberRecommendation.Item::productId).containsExactly(20L);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("ES로 계산했으면 저장한다")
    void savedWhenFromEs() {
        givenSnapshot();
        given(recommendPort.findCandidates(any())).willReturn(RecommendCandidates.withoutText(List.of(
                new RecommendCandidate(20L, 10L, 10_000, 0, Map.of()))));

        support.compute(MEMBER_ID);

        verify(store).save(any(), any(), any());
    }

    @Test
    @DisplayName("순위대로 고를 때 판매자당 최대 수로 걸러진 만큼 다음 묶음에서 채운다 (판매자 수는 묶음을 넘어 이어서 센다)")
    void pickInChunksFillsAcrossChunks() {
        // 1~8은 판매자 A, 9는 판매자 B. limit 4면 첫 묶음(8개)에서 A 3개만 고르고 다음 묶음에서 B를 채운다
        List<Long> ranked = LongStream.rangeClosed(1, 9).boxed().toList();
        given(summarySupport.toSummaries(anyList())).willAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(), id -> summary(id, id <= 8 ? 1L : 2L)));
        });

        List<ProductSummaryDto> picked = support.pickInChunks(ranked, 4, new HashSet<>());

        assertThat(picked).extracting(ProductSummaryDto::productId).containsExactly(1L, 2L, 3L, 9L);
    }

    private void givenSnapshot() {
        RecommendSeedProduct seed = new RecommendSeedProduct(new RecommendSeed(1L, RecommendBasis.WISH, 1.0), 10L, 10_000);
        ProductRecommendQuery query = new ProductRecommendQuery(List.of(), List.of(10L), List.of(10L), Set.of(1L), 30, 50);
        given(seedSupport.readMember(any(), any())).willReturn(new MemberSnapshot(List.of(seed), query, List.of(), Set.of(1L)));
        given(similarPort.findAllCandidates(anyList())).willReturn(List.of());
    }

    private static ProductSummaryDto summary(long productId, long sellerId) {
        return new ProductSummaryDto(productId, "상품" + productId, 10_000, null, null, sellerId, "상점", null, false, null, 0, 0.0);
    }
}
