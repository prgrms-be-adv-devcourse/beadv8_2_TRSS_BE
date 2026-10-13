package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.toss.TossPaymentException;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.boundedContext.payment.out.toss.TossTimeoutException;
import com.backend.global.exception.DomainException;

class PaymentConfirmTopupUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long TOPUP_ID = 7L;
    private static final String PAYMENT_KEY = "pk_1";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final long AMOUNT = 20_000;

    private final PaymentTopupConfirmProcessor processor = mock(PaymentTopupConfirmProcessor.class);
    private final TossPaymentsClient tossPaymentsClient = mock(TossPaymentsClient.class);
    private final PaymentConfirmTopupUseCase useCase = new PaymentConfirmTopupUseCase(processor, tossPaymentsClient);

    private final TossPaymentResponse tossDone =
            new TossPaymentResponse(PAYMENT_KEY, ORDER_ID, "DONE", AMOUNT, "카드", null);

    @Test
    @DisplayName("승인 성공: 선점 → 토스 승인 → 예치금 반영 결과를 돌려준다")
    void confirm_success() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).willReturn(tossDone);
        TopupConfirmDto done = new TopupConfirmDto(TOPUP_ID, ORDER_ID, TopupStatus.DONE, AMOUNT, 25_000L);
        given(processor.complete(TOPUP_ID, tossDone)).willReturn(done);

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(result).isEqualTo(done);
    }

    @Test
    @DisplayName("금액 불일치: 토스를 호출하지 않고 400 AMOUNT_MISMATCH")
    void confirm_amountMismatch() {
        given(processor.start(MEMBER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                .willReturn(new TopupConfirmStartResult(TOPUP_ID, TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH));

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-AMOUNT_MISMATCH");
        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("토스 응답 없음: 실패로 단정하지 않고 PROCESSING으로 응답한다")
    void confirm_timeout() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertProcessing(result);
    }

    @Test
    @DisplayName("이미 승인된 결제(ALREADY_PROCESSED_PAYMENT): 실패가 아니므로 PROCESSING으로 응답한다")
    void confirm_alreadyProcessedPayment() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(tossError(400, "ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertProcessing(result);
    }

    @Test
    @DisplayName("토스 5xx: 승인 여부가 불확실하므로 PROCESSING으로 응답한다")
    void confirm_tossServerError() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(tossError(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다."));

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertProcessing(result);
    }

    @Test
    @DisplayName("카드 거절(4xx): FAILED_PG_DECLINED로 기록하고 400 TOPUP_PAYMENT_DECLINED, data에 토스 코드·메시지")
    void confirm_declined() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(tossError(403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("400-TOPUP_PAYMENT_DECLINED");
                    assertThat(e.getData()).isEqualTo(Map.of(
                            "code", "REJECT_CARD_PAYMENT", "message", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));
                });
        verify(processor).fail(TOPUP_ID, TopupStatus.FAILED_PG_DECLINED, "한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
    }

    @Test
    @DisplayName("토스 인증 오류(401): FAILED_SYSTEM_ERROR로 기록하고 502 EXTERNAL_SERVICE_ERROR")
    void confirm_unauthorized() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(tossError(401, "UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."));

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("502-EXTERNAL_SERVICE_ERROR");
        verify(processor).fail(TOPUP_ID, TopupStatus.FAILED_SYSTEM_ERROR, "토스 인증에 실패했습니다.");
    }

    @Test
    @DisplayName("토스 승인 후 예치금 반영 실패: 오류로 끝내지 않고 PROCESSING으로 응답한다")
    void confirm_completeFails() {
        started();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).willReturn(tossDone);
        given(processor.complete(TOPUP_ID, tossDone)).willThrow(new RuntimeException("DB 오류"));

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertProcessing(result);
    }

    private void started() {
        given(processor.start(MEMBER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                .willReturn(new TopupConfirmStartResult(TOPUP_ID, TopupConfirmStartResult.Outcome.STARTED));
    }

    private static TossPaymentException tossError(int httpStatus, String code, String message) {
        return new TossPaymentException(httpStatus, code, message, new RuntimeException());
    }

    // 결과 확인 대기: 실패 기록도 반영도 하지 않음
    private void assertProcessing(TopupConfirmDto result) {
        assertThat(result.status()).isEqualTo(TopupStatus.PROCESSING);
        assertThat(result.topupId()).isEqualTo(TOPUP_ID);
        assertThat(result.balance()).isNull();
        verify(processor, never()).fail(anyLong(), any(), any());
    }
}
