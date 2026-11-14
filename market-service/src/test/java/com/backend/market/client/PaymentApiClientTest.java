package com.backend.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;

class PaymentApiClientTest {

    private static final String BASE_URL = "http://payment:8083";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String PAY_URL = BASE_URL + "/internal/v1/payments/pay";
    private static final String KEY = "ORDER-500-PAY-abc";
    private static final PayCommand COMMAND = new PayCommand(500L, "ORD-20261112-ABCDEFGH", 3L,
            21_500, 2_500, 1_000, LocalDateTime.of(2026, 11, 12, 10, 15), KEY);

    private MockRestServiceServer server;
    private PaymentApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PaymentApiClient(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("pay: POST /internal/v1/payments/pay에 내부 키 헤더와 PayCommand 본문(배송비·지원금·멱등키 포함)을 보내고 결과를 돌려준다")
    void pay_paid() {
        server.expect(requestTo(PAY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(jsonPath("$.orderId").value(500))
                .andExpect(jsonPath("$.amount").value(21_500))
                .andExpect(jsonPath("$.shippingFee").value(2_500))
                .andExpect(jsonPath("$.couponSubsidyAmount").value(1_000))
                .andExpect(jsonPath("$.idempotencyKey").value(KEY))
                .andRespond(withSuccess(rsData("""
                        {"paymentId": 77, "status": "PAID", "shortage": 0, "chargeAmount": 0}
                        """), MediaType.APPLICATION_JSON));

        PayResultDto result = client.pay(COMMAND);

        assertThat(result).isEqualTo(new PayResultDto(77L, PayResultStatus.PAID, 0, 0));
        server.verify();
    }

    @Test
    @DisplayName("pay: 잔액 부족·만료는 예외가 아니라 FAILED_* 결과로 돌려준다")
    void pay_failedResults() {
        server.expect(requestTo(PAY_URL))
                .andRespond(withSuccess(rsData("""
                        {"paymentId": null, "status": "FAILED_INSUFFICIENT_BALANCE", "shortage": 18000, "chargeAmount": 20000}
                        """), MediaType.APPLICATION_JSON));
        server.expect(requestTo(PAY_URL))
                .andRespond(withSuccess(rsData("""
                        {"paymentId": null, "status": "FAILED_ORDER_EXPIRED", "shortage": 0, "chargeAmount": 0}
                        """), MediaType.APPLICATION_JSON));

        assertThat(client.pay(COMMAND))
                .isEqualTo(new PayResultDto(null, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 18_000, 20_000));
        assertThat(client.pay(COMMAND).status()).isEqualTo(PayResultStatus.FAILED_ORDER_EXPIRED);
        server.verify();
    }

    @Test
    @DisplayName("pay: payment의 4xx RsData는 같은 resultCode·메시지의 DomainException")
    void pay_4xx_sameResultCode() {
        server.expect(requestTo(PAY_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "404-WALLET_NOT_FOUND", "msg": "지갑을 찾을 수 없습니다.", "data": null}
                        """));

        assertThatThrownBy(() -> client.pay(COMMAND))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("404-WALLET_NOT_FOUND");
                    assertThat(e.getMsg()).isEqualTo("지갑을 찾을 수 없습니다.");
                });
    }

    @Test
    @DisplayName("pay: 응답 시간 초과는 결과 불명(502-EXTERNAL_SERVICE_ERROR)")
    void pay_readTimeout_unknown() {
        server.expect(requestTo(PAY_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.pay(COMMAND))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    @Test
    @DisplayName("pay: 접속 실패·5xx·RsData가 아닌 4xx 본문·data 없음은 모두 결과 불명(502)")
    void pay_otherFailures_unknown() {
        server.expect(requestTo(PAY_URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(PAY_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"resultCode": "500-INTERNAL_ERROR", "msg": "일시적인 오류", "data": null}
                        """));
        server.expect(requestTo(PAY_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.TEXT_HTML).body("<html>bad gateway</html>"));
        server.expect(requestTo(PAY_URL)).andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.pay(COMMAND))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
        }
        server.verify();
    }

    @Test
    @DisplayName("cancelPayment: POST /internal/v1/payments/{idempotencyKey}/cancel에 내부 키 헤더를 넣는다")
    void cancelPayment() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/" + KEY + "/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "결제 취소를 처리했습니다.", "data": null}
                        """, MediaType.APPLICATION_JSON));

        client.cancelPayment(KEY);

        server.verify();
    }

    @Test
    @DisplayName("cancelPayment: 정산이 시작된 결제는 payment의 409-PAYMENT_NOT_CANCELABLE을 그대로 던진다")
    void cancelPayment_notCancelable() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/" + KEY + "/cancel"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "409-PAYMENT_NOT_CANCELABLE", "msg": "취소할 수 없는 결제입니다.", "data": null}
                        """));

        assertThatThrownBy(() -> client.cancelPayment(KEY))
                .extracting("resultCode").isEqualTo("409-PAYMENT_NOT_CANCELABLE");
    }

    @Test
    @DisplayName("getPaymentAttempts: GET /internal/v1/payments/orders/{orderId}/attempts에 내부 키 헤더를 넣고 목록을 돌려준다")
    void getPaymentAttempts() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/orders/500/attempts"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData("""
                        [{"paymentId": 77, "status": "PAID", "amount": 21500,
                          "attemptedAt": "2026-11-12T10:00:00", "paidAt": "2026-11-12T10:00:01", "refundedAt": null}]
                        """), MediaType.APPLICATION_JSON));

        List<PaymentAttemptDto> attempts = client.getPaymentAttempts(500L);

        assertThat(attempts).containsExactly(new PaymentAttemptDto(77L, PaymentAttemptStatus.PAID, 21_500,
                LocalDateTime.of(2026, 11, 12, 10, 0), LocalDateTime.of(2026, 11, 12, 10, 0, 1), null));
        server.verify();
    }

    @Test
    @DisplayName("getPaymentAttempts: 응답하지 못하면 502 (주문 상세는 결제 내역 없이 응답)")
    void getPaymentAttempts_unavailable() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/orders/500/attempts"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.getPaymentAttempts(500L))
                .extracting("resultCode").isEqualTo("502-EXTERNAL_SERVICE_ERROR");
    }

    private static String rsData(String dataJson) {
        return """
                {"resultCode": "200-1", "msg": "처리했습니다.", "data": %s}
                """.formatted(dataJson);
    }
}
