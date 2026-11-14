package com.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * 클라이언트가 연결을 먼저 끊은 예외(Broken pipe 등)가 ERROR를 남기는 handleUnknown이 아니라
 * handleClientAbort로 처리되는지 확인한다. Spring이 실제로 고르는 방식(ExceptionHandlerMethodResolver)으로 본다.
 */
class GlobalExceptionHandlerClientAbortTest {

    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    @DisplayName("연결이 끊긴 예외는 handleClientAbort로 처리된다")
    void clientAbort_isHandledSeparately() {
        AsyncRequestNotUsableException e = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write: java.io.IOException: Broken pipe", new IOException("Broken pipe"));

        Method method = resolver.resolveMethodByThrowable(e);

        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleClientAbort");
    }

    @Test
    @DisplayName("그 밖의 처리되지 않은 예외는 지금처럼 handleUnknown으로 처리된다")
    void otherException_stillHandledAsUnknown() {
        Method method = resolver.resolveMethodByThrowable(new IllegalStateException("boom"));

        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleUnknown");
    }
}
