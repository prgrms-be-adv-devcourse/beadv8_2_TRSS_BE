package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.ProductSearchIndexRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProductSearchIndexEventListenerTest {

    private final ProductFacade productFacade = mock(ProductFacade.class);
    private final ProductSearchIndexRequestedEvent event = new ProductSearchIndexRequestedEvent(Set.of(1L, 2L));

    @Test
    @DisplayName("요청받은 상품을 색인한다")
    void index() {
        new ProductSearchIndexEventListener(productFacade, Runnable::run).onIndexRequested(event);

        verify(productFacade).indexProducts(Set.of(1L, 2L));
    }

    @Test
    @DisplayName("색인이 실패해도 예외를 밖으로 내지 않는다 (로그만 남긴다)")
    void swallowIndexFailure() {
        willThrow(new IllegalStateException("ES down")).given(productFacade).indexProducts(any());
        ProductSearchIndexEventListener listener = new ProductSearchIndexEventListener(productFacade, Runnable::run);

        assertThatCode(() -> listener.onIndexRequested(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("대기열이 가득 차면 요청을 버리고 예외를 내지 않는다")
    void dropWhenQueueFull() {
        ProductSearchIndexEventListener listener = new ProductSearchIndexEventListener(productFacade,
                task -> { throw new RejectedExecutionException("full"); });

        assertThatCode(() -> listener.onIndexRequested(event)).doesNotThrowAnyException();
        verify(productFacade, never()).indexProducts(any());
    }
}
