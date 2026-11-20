package com.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * 비동기 요청 시간 초과가 ERROR를 남기는 handleUnknown이 아니라 handleAsyncTimeout으로 처리되는지 확인함
 */
class GlobalExceptionHandlerAsyncTimeoutTest {

    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    @DisplayName("비동기 요청 시간 초과는 handleAsyncTimeout으로 처리된다")
    void asyncTimeout_isHandledSeparately() {
        Method method = resolver.resolveMethodByThrowable(new AsyncRequestTimeoutException());

        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleAsyncTimeout");
    }
}
