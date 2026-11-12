package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;

class PaymentTopupMethodCancellerTest {

    private static final Long TOPUP_ID = 7L;
    private static final long AMOUNT = 20_000;

    private final PaymentTopupConfirmProcessor processor = mock(PaymentTopupConfirmProcessor.class);
    private final TopupGateways topupGateways = mock(TopupGateways.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentTopupMethodCanceller canceller = new PaymentTopupMethodCanceller(processor, topupGateways);

    @BeforeEach
    void setUp() {
        given(topupGateways.get(PgProvider.TOSS)).willReturn(gateway);
    }

    @Test
    @DisplayName("첫 취소: METHOD_CANCELING으로 바꾸고 충전 금액 전부를 PG에 취소한 뒤 확정한다")
    void cancelApproved() {
        given(processor.startMethodCancel(TOPUP_ID)).willReturn(Optional.of(start(1)));

        canceller.cancelApproved(TOPUP_ID, PgPaymentMethod.OTHER);

        verify(gateway).cancel("pk_1", AMOUNT, "충전에 허용하지 않은 결제 수단", "TOPUP-7-METHOD-CANCEL-1");
        verify(processor).completeMethodCancel(TOPUP_ID, 1, "허용하지 않은 결제 수단이라 PG에서 결제를 취소했습니다. (OTHER)");
    }

    @Test
    @DisplayName("첫 취소: PG 결과를 모르면 확정하지 않고 예외도 던지지 않는다 (METHOD_CANCELING으로 남아 대사가 다시 취소)")
    void cancelApproved_unknown() {
        given(processor.startMethodCancel(TOPUP_ID)).willReturn(Optional.of(start(1)));
        given(gateway.cancel(anyString(), anyLong(), anyString(), anyString()))
                .willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));

        canceller.cancelApproved(TOPUP_ID, PgPaymentMethod.OTHER);

        verify(processor, never()).completeMethodCancel(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("첫 취소: PROCESSING이 아니면(다른 쪽이 먼저 처리) PG 취소를 보내지 않는다")
    void cancelApproved_alreadyHandled() {
        given(processor.startMethodCancel(TOPUP_ID)).willReturn(Optional.empty());

        canceller.cancelApproved(TOPUP_ID, PgPaymentMethod.OTHER);

        verify(gateway, never()).cancel(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("재시도: 회차를 늘린 새 멱등 키로 취소하고 확정되면 true")
    void retry() {
        given(processor.retryMethodCancel(TOPUP_ID, 1)).willReturn(Optional.of(start(2)));

        assertThat(canceller.retry(TOPUP_ID, 1, PgPaymentMethod.OTHER)).isTrue();

        verify(gateway).cancel("pk_1", AMOUNT, "충전에 허용하지 않은 결제 수단", "TOPUP-7-METHOD-CANCEL-2");
        verify(processor).completeMethodCancel(eq(TOPUP_ID), eq(2), anyString());
    }

    @Test
    @DisplayName("재시도: PG가 거절하면 false (예외를 던지지 않음)")
    void retry_declined() {
        given(processor.retryMethodCancel(TOPUP_ID, 1)).willReturn(Optional.of(start(2)));
        given(gateway.cancel(anyString(), anyLong(), anyString(), anyString()))
                .willThrow(new PgDeclinedException("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.", null));

        assertThat(canceller.retry(TOPUP_ID, 1, PgPaymentMethod.OTHER)).isFalse();

        verify(processor, never()).completeMethodCancel(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("재시도: 그사이 회차가 바뀌었거나 확정됐으면 PG 취소를 보내지 않고 false")
    void retry_attemptChanged() {
        given(processor.retryMethodCancel(TOPUP_ID, 1)).willReturn(Optional.empty());

        assertThat(canceller.retry(TOPUP_ID, 1, PgPaymentMethod.OTHER)).isFalse();

        verify(gateway, never()).cancel(anyString(), anyLong(), anyString(), anyString());
    }

    private static TopupMethodCancelStart start(int attempt) {
        return new TopupMethodCancelStart(TOPUP_ID, PgProvider.TOSS, "pk_1", AMOUNT, attempt, "TOPUP-7-METHOD-CANCEL-" + attempt);
    }
}
