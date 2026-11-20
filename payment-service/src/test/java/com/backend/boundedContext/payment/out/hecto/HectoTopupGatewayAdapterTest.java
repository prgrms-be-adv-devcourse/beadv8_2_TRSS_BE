package com.backend.boundedContext.payment.out.hecto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgCancelRequest;
import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;

class HectoTopupGatewayAdapterTest {

    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final String TRD_NO = "STFP_PGCAtest0261114220111M1522521";
    private static final long AMOUNT = 20_000;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 15, 10, 0);
    private static final LocalDateTime TRANSACTED_AT = LocalDateTime.of(2026, 11, 15, 10, 1, 5);
    private static final PgPaymentRef PAYMENT = new PgPaymentRef(ORDER_ID, TRD_NO, AMOUNT, REQUESTED_AT, null);

    private final HectoClient hectoClient = mock(HectoClient.class);
    private final HectoCheckout hectoCheckout = mock(HectoCheckout.class);
    private final HectoTopupGatewayAdapter adapter = new HectoTopupGatewayAdapter(hectoClient, hectoCheckout);

    @Test
    @DisplayName("PG는 HECTO이고, 결제창을 닫아 돌아온 USER_CANCEL만 사용자 취소로 본다")
    void providerAndUserCancel() {
        assertThat(adapter.provider()).isEqualTo(PgProvider.HECTO);
        assertThat(adapter.isUserCancel("USER_CANCEL")).isTrue();
        assertThat(adapter.isUserCancel("ST09")).isFalse();
        assertThat(adapter.isUserCancel(null)).isFalse();
    }

    @Test
    @DisplayName("결제창 요청 값은 HectoCheckout이 만들고, 키가 없어 만들지 못하면 PgConfigurationException")
    void checkout() {
        PgCheckout checkout = new PgCheckout("https://window", Map.of("mchtTrdNo", ORDER_ID));
        given(hectoCheckout.create(PAYMENT)).willReturn(checkout);
        assertThat(adapter.checkout(PAYMENT)).contains(checkout);

        given(hectoCheckout.create(PAYMENT)).willThrow(new IllegalStateException("헥토 금액 암호화·복호화에 실패했습니다."));
        assertThatThrownBy(() -> adapter.checkout(PAYMENT)).isInstanceOf(PgConfigurationException.class);
    }

    @Test
    @DisplayName("승인 성공(0021)이면 APPROVED, 결제 수단 CARD, 헥토 처리 일시를 승인 일시로 돌려준다")
    void confirm_approved() {
        given(hectoClient.approve(eq(ORDER_ID), eq(TRD_NO), eq(AMOUNT), any())).willReturn(success("0000", AMOUNT, null, null));

        PgPaymentResult result = adapter.confirm(TRD_NO, ORDER_ID, AMOUNT);

        assertThat(result).isEqualTo(new PgPaymentResult(TRD_NO, ORDER_ID, PgPaymentStatus.APPROVED, "0000", AMOUNT, 0, PgPaymentMethod.CARD, TRANSACTED_AT));
    }

    @Test
    @DisplayName("승인 실패(0031)는 승인되지 않음이 확정돼 PgDeclinedException (헥토 코드·메시지)")
    void confirm_declined() {
        given(hectoClient.approve(any(), any(), eq(AMOUNT), any())).willReturn(fail("ST09", "거래금액이 유효하지 않습니다."));

        assertThatThrownBy(() -> adapter.confirm(TRD_NO, ORDER_ID, AMOUNT))
                .isInstanceOf(PgDeclinedException.class)
                .satisfies(e -> {
                    assertThat(((PgDeclinedException) e).getCode()).isEqualTo("ST09");
                    assertThat(((PgDeclinedException) e).getPgMessage()).isEqualTo("거래금액이 유효하지 않습니다.");
                });
    }

    @Test
    @DisplayName("승인 실패라도 ST50(중복결제)은 앞선 승인이 처리됐다는 뜻이라 거절이 아니라 PgResultUnknownException")
    void confirm_duplicate() {
        given(hectoClient.approve(any(), any(), eq(AMOUNT), any())).willReturn(fail("ST50", "중복결제 시도로 실패되었습니다. 잠시 후 다시 시도 부탁드립니다."));

        assertThatThrownBy(() -> adapter.confirm(TRD_NO, ORDER_ID, AMOUNT)).isInstanceOf(PgResultUnknownException.class);
    }

    @Test
    @DisplayName("헥토는 두 번째 승인을 거절하므로 처리되지 않은 승인을 다시 보낼 수 있다")
    void canRetryConfirm() {
        assertThat(adapter.canRetryConfirm()).isTrue();
    }

    @Test
    @DisplayName("헥토 결과를 알 수 없으면(응답 없음·해시 불일치 등) PgResultUnknownException")
    void confirm_unknown() {
        given(hectoClient.approve(any(), any(), eq(AMOUNT), any())).willThrow(new HectoResultUnknownException("헥토 승인 응답의 해시가 맞지 않습니다."));

        assertThatThrownBy(() -> adapter.confirm(TRD_NO, ORDER_ID, AMOUNT)).isInstanceOf(PgResultUnknownException.class);
    }

    @Test
    @DisplayName("암호화 키가 없어 요청을 만들지 못하면 PgConfigurationException")
    void confirm_notConfigured() {
        given(hectoClient.approve(any(), any(), eq(AMOUNT), any())).willThrow(new IllegalStateException("헥토 금액 암호화·복호화에 실패했습니다."));

        assertThatThrownBy(() -> adapter.confirm(TRD_NO, ORDER_ID, AMOUNT)).isInstanceOf(PgConfigurationException.class);
    }

    @ParameterizedTest(name = "조회 {0}, 취소 금액 {1}원 → {2}")
    @CsvSource({
            "0000, 0, APPROVED",
            "0000, 5000, PARTIALLY_CANCELED",
            "0001, 5000, PARTIALLY_CANCELED",
            "0001, 20000, CANCELED",
            "0009, 0, NOT_APPROVED",
            "9999, 0, PENDING"
    })
    @DisplayName("조회 결과 코드와 누적 취소 금액으로 PG 결제 상태를 정한다")
    void getPayment_status(String outRsltCd, long canceledAmount, PgPaymentStatus expected) {
        given(hectoClient.inquiry(eq(ORDER_ID), eq(TRD_NO), eq(AMOUNT), eq(REQUESTED_AT), any())).willReturn(success(outRsltCd, AMOUNT, canceledAmount, null));

        PgPaymentResult result = adapter.getPayment(PAYMENT);

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.pgStatus()).isEqualTo(outRsltCd);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.totalAmount()).isEqualTo(AMOUNT);
        assertThat(result.canceledAmount()).isEqualTo(canceledAmount);
        assertThat(result.method()).isEqualTo(PgPaymentMethod.CARD);
    }

    @Test
    @DisplayName("조회 실패 ST06(거래 없음)은 PgPaymentNotFoundException")
    void getPayment_notFound() {
        given(hectoClient.inquiry(any(), any(), eq(AMOUNT), any(), any())).willReturn(fail("ST06", "거래번호 정보가 존재하지 않습니다."));

        assertThatThrownBy(() -> adapter.getPayment(PAYMENT)).isInstanceOf(PgPaymentNotFoundException.class);
    }

    @Test
    @DisplayName("자정을 넘긴 충전 건: 결제 요청 날짜로 거래가 없으면 승인 요청 날짜로 한 번 더 조회한다")
    void getPayment_otherDay_found() {
        LocalDateTime confirmRequestedAt = LocalDateTime.of(2026, 11, 16, 0, 1);
        PgPaymentRef overMidnight = new PgPaymentRef(ORDER_ID, TRD_NO, AMOUNT, REQUESTED_AT, confirmRequestedAt);
        given(hectoClient.inquiry(eq(ORDER_ID), eq(TRD_NO), eq(AMOUNT), eq(REQUESTED_AT), any())).willReturn(fail("ST06", "거래번호 정보가 존재하지 않습니다."));
        given(hectoClient.inquiry(eq(ORDER_ID), eq(TRD_NO), eq(AMOUNT), eq(confirmRequestedAt), any())).willReturn(success("0000", AMOUNT, 0L, null));

        assertThat(adapter.getPayment(overMidnight).status()).isEqualTo(PgPaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("자정을 넘긴 충전 건: 두 날짜 모두 거래가 없어야 PgPaymentNotFoundException")
    void getPayment_otherDay_notFound() {
        PgPaymentRef overMidnight = new PgPaymentRef(ORDER_ID, TRD_NO, AMOUNT, REQUESTED_AT, LocalDateTime.of(2026, 11, 16, 0, 1));
        given(hectoClient.inquiry(any(), any(), eq(AMOUNT), any(), any())).willReturn(fail("ST06", "거래번호 정보가 존재하지 않습니다."));

        assertThatThrownBy(() -> adapter.getPayment(overMidnight)).isInstanceOf(PgPaymentNotFoundException.class);
        verify(hectoClient, times(2)).inquiry(any(), any(), eq(AMOUNT), any(), any());
    }

    @Test
    @DisplayName("승인 요청 날짜가 같거나 없으면 다시 조회하지 않는다")
    void getPayment_sameDay_noRetry() {
        PgPaymentRef sameDay = new PgPaymentRef(ORDER_ID, TRD_NO, AMOUNT, REQUESTED_AT, REQUESTED_AT.plusMinutes(3));
        given(hectoClient.inquiry(any(), any(), eq(AMOUNT), any(), any())).willReturn(fail("ST06", "거래번호 정보가 존재하지 않습니다."));

        assertThatThrownBy(() -> adapter.getPayment(sameDay)).isInstanceOf(PgPaymentNotFoundException.class);
        assertThatThrownBy(() -> adapter.getPayment(PAYMENT)).isInstanceOf(PgPaymentNotFoundException.class);
        verify(hectoClient, times(2)).inquiry(any(), any(), eq(AMOUNT), eq(REQUESTED_AT), any());
    }

    @Test
    @DisplayName("그 외 조회 실패는 다음에 다시 확인하도록 PgResultUnknownException")
    void getPayment_otherFailure() {
        given(hectoClient.inquiry(any(), any(), eq(AMOUNT), any(), any())).willReturn(fail("ST09", "거래금액이 유효하지 않습니다."));

        assertThatThrownBy(() -> adapter.getPayment(PAYMENT)).isInstanceOf(PgResultUnknownException.class);
    }

    @ParameterizedTest(name = "남은 금액 {0}원 → {1}, 취소 합계 {2}원")
    @CsvSource({
            "5000, PARTIALLY_CANCELED, 15000",
            "0, CANCELED, 20000"
    })
    @DisplayName("취소 성공이면 남은 금액으로 전액·부분 취소를 정하고, 취소 합계는 충전 금액 - 남은 금액")
    void cancel_success(long balanceAmount, PgPaymentStatus expected, long canceledAmount) {
        given(hectoClient.cancel(eq(ORDER_ID), eq(TRD_NO), eq(3), eq(15_000L), any())).willReturn(success("0000", null, 15_000L, balanceAmount));

        PgPaymentResult result = adapter.cancel(new PgCancelRequest(PAYMENT, 15_000, "예치금 충전 취소", 3, "TOPUP-7-CANCEL-3"));

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.canceledAmount()).isEqualTo(canceledAmount);
    }

    @Test
    @DisplayName("취소됐을 가능성이 없는 실패(ST09 금액 오류 등)는 PgDeclinedException (헥토 코드·메시지)")
    void cancel_declined() {
        given(hectoClient.cancel(any(), any(), eq(1), eq(15_000L), any())).willReturn(fail("ST09", "거래금액이 유효하지 않습니다."));

        assertThatThrownBy(() -> adapter.cancel(new PgCancelRequest(PAYMENT, 15_000, "예치금 충전 취소", 1, "TOPUP-7-CANCEL-1")))
                .isInstanceOfSatisfying(PgDeclinedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ST09");
                    assertThat(e.getPgMessage()).isEqualTo("거래금액이 유효하지 않습니다.");
                });
    }

    @Test
    @DisplayName("취소 금액 초과(ST57)는 상점관리자에서 일부가 이미 취소됐을 수 있어 PgResultUnknownException")
    void cancel_overBalance() {
        given(hectoClient.cancel(any(), any(), eq(1), eq(15_000L), any())).willReturn(fail("ST57", "취소금액(환불금액)이 원거래금액을 초과합니다."));

        assertThatThrownBy(() -> adapter.cancel(new PgCancelRequest(PAYMENT, 15_000, "예치금 충전 취소", 1, "TOPUP-7-CANCEL-1")))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @Test
    @DisplayName("이미 취소 완료(ST25)는 앞선 요청으로 취소됐을 수 있어 PgResultUnknownException (되돌리지 않고 조회로 확정)")
    void cancel_alreadyCanceled() {
        given(hectoClient.cancel(any(), any(), eq(2), eq(15_000L), any())).willReturn(fail("ST25", "선택하신 내역은 이미 취소가 완료 되었습니다."));

        assertThatThrownBy(() -> adapter.cancel(new PgCancelRequest(PAYMENT, 15_000, "예치금 충전 취소", 2, "TOPUP-7-CANCEL-2")))
                .isInstanceOf(PgResultUnknownException.class);
    }

    @Test
    @DisplayName("취소 결과를 알 수 없으면 PgResultUnknownException")
    void cancel_unknown() {
        given(hectoClient.cancel(any(), any(), eq(1), eq(15_000L), any())).willThrow(new HectoResultUnknownException(new RuntimeException("Read timed out")));

        assertThatThrownBy(() -> adapter.cancel(new PgCancelRequest(PAYMENT, 15_000, "예치금 충전 취소", 1, "TOPUP-7-CANCEL-1")))
                .isInstanceOf(PgResultUnknownException.class);
    }

    private static HectoResponse success(String outRsltCd, Long amount, Long canceledAmount, Long balanceAmount) {
        return new HectoResponse("0021", outRsltCd, "정상처리되었습니다", ORDER_ID, TRD_NO, TRANSACTED_AT, amount, canceledAmount, balanceAmount);
    }

    private static HectoResponse fail(String outRsltCd, String message) {
        return new HectoResponse("0031", outRsltCd, message, ORDER_ID, "", TRANSACTED_AT, null, null, null);
    }
}
