package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 검색 alias 복구 스케줄러: 실패해도 예외를 밖으로 던지지 않고, 다음 주기에 다시 시도한다 */
@ExtendWith(MockitoExtension.class)
class ProductSearchIndexRecoverySchedulerTest {

    @Mock
    private ProductFacade productFacade;

    @InjectMocks
    private ProductSearchIndexRecoveryScheduler scheduler;

    @Test
    @DisplayName("기동 때 ES가 응답하지 않아 실패해도, 다음 주기에 다시 확인해 재색인한다")
    void retriesNextCycleAfterFailure() {
        given(productFacade.rebuildSearchIndexIfOutdated())
                .willThrow(new UncheckedIOException("ES 접속 실패", new IOException()))
                .willReturn(OptionalInt.of(33))
                .willReturn(OptionalInt.empty());

        assertThatCode(scheduler::rebuildIfOutdated).doesNotThrowAnyException();   // 실패: ERROR 로그만
        scheduler.rebuildIfOutdated();   // ES가 돌아옴: 재색인
        scheduler.rebuildIfOutdated();   // 최신: 아무것도 안 함

        verify(productFacade, times(3)).rebuildSearchIndexIfOutdated();
    }
}
