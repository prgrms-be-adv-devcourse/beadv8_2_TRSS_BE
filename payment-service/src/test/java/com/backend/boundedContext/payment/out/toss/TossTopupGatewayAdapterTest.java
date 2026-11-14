package com.backend.boundedContext.payment.out.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;

@ExtendWith(MockitoExtension.class)
class TossTopupGatewayAdapterTest {

    private static final String PAYMENT_KEY = "pk_1";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final long AMOUNT = 20_000;
    private static final String REASON = "충전 취소";
    private static final String CANCEL_KEY = "TOPUP-7-CANCEL-0";

    @Mock
    private TossPaymentsClient tossPaymentsClient;

    @InjectMocks
    private TossTopupGatewayAdapter adapter;

    @Test
    @DisplayName("PG는 토스")
    void provider() {
        assertThat(adapter.provider()).isEqualTo(PgProvider.TOSS);
    }

    @Test
    @DisplayName("승인: 토스 응답을 PG 결과로 바꾼다 (DONE → APPROVED, 카드 → CARD, 승인 시각은 KST)")
    void confirm_success() {
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(toss("DONE", "카드", OffsetDateTime.of(2026, 10, 12, 6, 0, 0, 0, ZoneOffset.UTC)));

        PgPaymentResult result = adapter.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(result.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.totalAmount()).isEqualTo(AMOUNT);
        assertThat(result.canceledAmount()).isZero();
        assertThat(result.method()).isEqualTo(PgPaymentMethod.CARD);
        assertThat(result.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 15, 0));
    }

    @ParameterizedTest(name = "토스 status {0} → {1}")
    @CsvSource({
            "DONE, APPROVED",
            "ABORTED, NOT_APPROVED",
            "EXPIRED, NOT_APPROVED",
            "CANCELED, CANCELED",
            "IN_PROGRESS, PENDING",
            "READY, PENDING",
            "PARTIAL_CANCELED, PARTIALLY_CANCELED",
    })
    @DisplayName("토스 결제 status를 PG 결제 상태로 바꾸고, 원래 값도 담는다")
    void status(String tossStatus, PgPaymentStatus expected) {
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(toss(tossStatus, "카드", null));

        PgPaymentResult result = adapter.getPayment(PAYMENT_KEY);

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.pgStatus()).isEqualTo(tossStatus);   // 원래 값은 기록용으로 그대로 담음
    }

    @ParameterizedTest(name = "토스 method {0} → {1}")
    @CsvSource({
            "카드, CARD",
            "간편결제, EASY_PAY",
            "계좌이체, TRANSFER",
            "가상계좌, OTHER",
            "휴대폰, OTHER",
            "문화상품권, OTHER",
    })
    @DisplayName("토스 결제 method(한글 값)를 결제 수단으로 바꾼다 (결제창에 없는 수단은 OTHER)")
    void method(String tossMethod, PgPaymentMethod expected) {
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(toss("DONE", tossMethod, null));

        assertThat(adapter.getPayment(PAYMENT_KEY).method()).isEqualTo(expected);
    }

    @Test
    @DisplayName("method가 없으면 OTHER, 승인 시각이 없으면 null")
    void method_null() {
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(toss("IN_PROGRESS", null, null));

        PgPaymentResult result = adapter.getPayment(PAYMENT_KEY);

        assertThat(result.method()).isEqualTo(PgPaymentMethod.OTHER);
        assertThat(result.approvedAt()).isNull();
    }

    @Test
    @DisplayName("승인: 응답을 받지 못하면 결과 모름")
    void confirm_timeout() {
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));

        assertThatThrownBy(() -> adapter.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @ParameterizedTest(name = "HTTP {0} {1} → 결과 모름")
    @CsvSource({
            "500, FAILED_INTERNAL_SYSTEM_PROCESSING",
            "503, UNKNOWN",
            "400, ALREADY_PROCESSED_PAYMENT",
    })
    @DisplayName("승인: 5xx·이미 처리된 결제는 결과 모름")
    void confirm_unknown(int httpStatus, String code) {
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new TossPaymentException(httpStatus, code, "메시지", null));

        assertThatThrownBy(() -> adapter.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @ParameterizedTest(name = "HTTP {0} {1} → 설정 오류")
    @CsvSource({
            "401, UNAUTHORIZED_KEY",
            "400, INVALID_API_KEY",
            "401, UNKNOWN",
    })
    @DisplayName("승인: 401·API 키 오류 코드는 설정 오류")
    void confirm_configuration(int httpStatus, String code) {
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new TossPaymentException(httpStatus, code, "메시지", null));

        assertThatThrownBy(() -> adapter.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOfSatisfying(PgConfigurationException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("승인: 그 외 4xx(카드 거절 등)는 거절, 토스 코드·메시지를 담는다")
    void confirm_declined() {
        given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new TossPaymentException(403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.", null));

        assertThatThrownBy(() -> adapter.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOfSatisfying(PgDeclinedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REJECT_CARD_PAYMENT");
                    assertThat(e.getPgMessage()).isEqualTo("한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
                });
    }

    @Test
    @DisplayName("조회: 404 NOT_FOUND_PAYMENT면 결제 없음")
    void getPayment_notFound() {
        given(tossPaymentsClient.getPayment(PAYMENT_KEY))
                .willThrow(new TossPaymentException(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.", null));

        assertThatThrownBy(() -> adapter.getPayment(PAYMENT_KEY))
                .isInstanceOf(PgPaymentNotFoundException.class);
    }

    @ParameterizedTest(name = "HTTP {0} {1} → 결과 모름")
    @CsvSource({
            "404, NOT_FOUND",
            "500, FAILED_INTERNAL_SYSTEM_PROCESSING",
            "401, UNAUTHORIZED_KEY",
    })
    @DisplayName("조회: 결제 없음 외의 오류는 결과 모름 (다음에 다시 확인)")
    void getPayment_unknown(int httpStatus, String code) {
        given(tossPaymentsClient.getPayment(PAYMENT_KEY))
                .willThrow(new TossPaymentException(httpStatus, code, "메시지", null));

        assertThatThrownBy(() -> adapter.getPayment(PAYMENT_KEY))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @Test
    @DisplayName("조회: 응답을 받지 못하면 결과 모름")
    void getPayment_timeout() {
        given(tossPaymentsClient.getPayment(PAYMENT_KEY))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));

        assertThatThrownBy(() -> adapter.getPayment(PAYMENT_KEY))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @Test
    @DisplayName("취소: 결제키·금액·사유·멱등 키를 그대로 넘기고, 취소된 금액 합계를 결과에 담는다")
    void cancel_success() {
        TossPaymentResponse canceled = new TossPaymentResponse(PAYMENT_KEY, ORDER_ID, "PARTIAL_CANCELED", AMOUNT, "카드", null,
                List.of(new TossPaymentResponse.Cancel(15_000, "DONE")));
        given(tossPaymentsClient.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY)).willReturn(canceled);

        PgPaymentResult result = adapter.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY);

        assertThat(result.status()).isEqualTo(PgPaymentStatus.PARTIALLY_CANCELED);
        assertThat(result.canceledAmount()).isEqualTo(15_000);
        assertThat(result.totalAmount()).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("취소: 응답을 받지 못하면 결과 모름")
    void cancel_timeout() {
        given(tossPaymentsClient.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));

        assertThatThrownBy(() -> adapter.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @ParameterizedTest(name = "HTTP {0} {1} → 결과 모름")
    @CsvSource({
            "500, FAILED_INTERNAL_SYSTEM_PROCESSING",
            "500, FAILED_REFUND_PROCESS",
            "400, ALREADY_CANCELED_PAYMENT",
            "409, IDEMPOTENT_REQUEST_PROCESSING",
            "400, ALREADY_PROCESSING_REQUEST",
            "403, FORBIDDEN_CONSECUTIVE_REQUEST",
            "400, PROVIDER_ERROR",
    })
    @DisplayName("취소: 5xx·이미 취소됨·앞선 요청 처리 중·결제사 일시 오류는 결과 모름 (조회로 확정)")
    void cancel_unknown(int httpStatus, String code) {
        given(tossPaymentsClient.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .willThrow(new TossPaymentException(httpStatus, code, "메시지", null));

        assertThatThrownBy(() -> adapter.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @ParameterizedTest(name = "HTTP {0} {1} → 설정 오류")
    @CsvSource({
            "401, UNAUTHORIZED_KEY",
            "400, INVALID_API_KEY",
    })
    @DisplayName("취소: 401·API 키 오류 코드는 설정 오류")
    void cancel_configuration(int httpStatus, String code) {
        given(tossPaymentsClient.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .willThrow(new TossPaymentException(httpStatus, code, "메시지", null));

        assertThatThrownBy(() -> adapter.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .isInstanceOfSatisfying(PgConfigurationException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @ParameterizedTest(name = "HTTP {0} {1} → 거절")
    @CsvSource({
            "403, EXCEED_MAX_REFUND_DUE",
            "403, NOT_ALLOWED_PARTIAL_REFUND",
            "403, NOT_CANCELABLE_PAYMENT",
            "404, NOT_FOUND_PAYMENT",
    })
    @DisplayName("취소: 그 외 4xx(환불 기간 지남·부분 취소 불가 등)는 거절, 토스 코드·메시지를 담는다")
    void cancel_declined(int httpStatus, String code) {
        given(tossPaymentsClient.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .willThrow(new TossPaymentException(httpStatus, code, "취소할 수 없습니다.", null));

        assertThatThrownBy(() -> adapter.cancel(PAYMENT_KEY, 15_000, REASON, CANCEL_KEY))
                .isInstanceOfSatisfying(PgDeclinedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.getPgMessage()).isEqualTo("취소할 수 없습니다.");
                });
    }

    private static TossPaymentResponse toss(String status, String method, OffsetDateTime approvedAt) {
        return new TossPaymentResponse(PAYMENT_KEY, ORDER_ID, status, AMOUNT, method, approvedAt, null);
    }
}
