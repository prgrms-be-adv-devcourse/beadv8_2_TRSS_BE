package com.backend.boundedContext.product.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 뒤에서 다시 계산하기: 같은 회원은 계산이 끝날 때까지 한 번만, 실패·대기열 초과 뒤에도 다음 요청은 다시 맡는다 */
class ProductRecommendRefresherTest {

    private final ProductRecommendSupport support = mock(ProductRecommendSupport.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final ProductRecommendRefresher refresher = new ProductRecommendRefresher(support, queued::add);

    @Test
    @DisplayName("같은 회원을 계산하는 중에 다시 요청해도 한 번만 맡고, 끝난 뒤에는 다시 맡는다")
    void onePerMemberWhileRunning() {
        refresher.refreshLater(1L);
        refresher.refreshLater(1L);
        refresher.refreshLater(2L);
        queued.forEach(Runnable::run);

        verify(support, times(1)).compute(1L);
        verify(support, times(1)).compute(2L);

        queued.clear();
        refresher.refreshLater(1L);
        queued.forEach(Runnable::run);
        verify(support, times(2)).compute(1L);
    }

    @Test
    @DisplayName("계산이 실패해도 다음 요청은 다시 맡는다")
    void retryAfterFailure() {
        willThrow(new IllegalStateException("ES 오류")).given(support).compute(1L);

        refresher.refreshLater(1L);
        queued.forEach(Runnable::run);
        queued.clear();
        refresher.refreshLater(1L);
        queued.forEach(Runnable::run);

        verify(support, times(2)).compute(1L);
    }

    @Test
    @DisplayName("대기열이 가득 차 거절되면 건너뛰고, 다음 요청은 다시 맡는다")
    void retryAfterRejected() {
        List<Runnable> accepted = new ArrayList<>();
        boolean[] full = {true};
        Executor executor = runnable -> {
            if (full[0]) throw new RejectedExecutionException("가득 참");
            accepted.add(runnable);
        };
        ProductRecommendRefresher rejecting = new ProductRecommendRefresher(support, executor);

        rejecting.refreshLater(1L);
        full[0] = false;
        rejecting.refreshLater(1L);
        accepted.forEach(Runnable::run);

        verify(support, times(1)).compute(1L);
    }
}
