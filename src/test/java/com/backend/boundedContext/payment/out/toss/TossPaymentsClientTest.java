package com.backend.boundedContext.payment.out.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentsClientTest {

    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final String SECRET_KEY = "test_sk_dummy";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";

    private static final String PAYMENT_JSON = """
            {
              "paymentKey": "pk_1",
              "orderId": "%s",
              "status": "DONE",
              "totalAmount": 20000,
              "method": "카드",
              "approvedAt": "2026-10-12T15:00:00+09:00",
              "card": {"company": "현대"}
            }
            """.formatted(ORDER_ID);

    private MockRestServiceServer server;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder, BASE_URL, SECRET_KEY);
    }

    @Test
    @DisplayName("승인: 시크릿 키 Basic 인증으로 결제키·주문번호·금액을 보내고 결제 결과를 받는다")
    void confirm_success() {
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expectedAuth))
                .andExpect(jsonPath("$.paymentKey").value("pk_1"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.amount").value(20000))
                .andRespond(withSuccess(PAYMENT_JSON, MediaType.APPLICATION_JSON));

        TossPaymentResponse response = client.confirm("pk_1", ORDER_ID, 20_000);

        server.verify();
        assertThat(response.paymentKey()).isEqualTo("pk_1");
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.isApproved()).isTrue();
        assertThat(response.totalAmount()).isEqualTo(20_000);
        assertThat(response.method()).isEqualTo("카드");
        assertThat(response.approvedAtKst()).isEqualTo(LocalDateTime.of(2026, 10, 12, 15, 0));
    }

    @Test
    @DisplayName("승인: 토스가 오류로 응답하면 상태·코드·메시지를 담은 TossPaymentException (502)")
    void confirm_errorResponse() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족으로 결제에 실패했습니다.\"}"));

        assertThatThrownBy(() -> client.confirm("pk_1", ORDER_ID, 20_000))
                .isInstanceOfSatisfying(TossPaymentException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(403);
                    assertThat(e.getCode()).isEqualTo("REJECT_CARD_PAYMENT");
                    assertThat(e.getTossMessage()).isEqualTo("한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
                    assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR");
                });
    }

    @Test
    @DisplayName("승인: 오류 본문을 해석할 수 없어도 TossPaymentException으로 감싼다")
    void confirm_unparsableErrorResponse() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_HTML)
                        .body("<html>error</html>"));

        assertThatThrownBy(() -> client.confirm("pk_1", ORDER_ID, 20_000))
                .isInstanceOfSatisfying(TossPaymentException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(500);
                    assertThat(e.getCode()).isEqualTo("UNKNOWN");
                });
    }

    @Test
    @DisplayName("승인: 응답을 받지 못하면 TossTimeoutException")
    void confirm_timeout() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.confirm("pk_1", ORDER_ID, 20_000))
                .isInstanceOf(TossTimeoutException.class);
    }

    @Test
    @DisplayName("주문번호 조회: 주문번호로 결제 결과를 받는다")
    void getPaymentByOrderId_success() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/" + ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PAYMENT_JSON, MediaType.APPLICATION_JSON));

        TossPaymentResponse response = client.getPaymentByOrderId(ORDER_ID);

        server.verify();
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.isApproved()).isTrue();
    }

    @Test
    @DisplayName("주문번호 조회: 결제가 없으면 TossPaymentException (404)")
    void getPaymentByOrderId_notFound() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/" + ORDER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}"));

        assertThatThrownBy(() -> client.getPaymentByOrderId(ORDER_ID))
                .isInstanceOfSatisfying(TossPaymentException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND_PAYMENT");
                });
    }
}
