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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class TossPaymentsClientTest {

    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final String SECRET_KEY = "test_sk_dummy";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";

    // 실제 HTTP 서버 테스트용 짧은 제한 시간
    private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration TEST_READ_TIMEOUT = Duration.ofMillis(500);

    // 실제 HTTP 서버가 마지막으로 받은 요청의 프로토콜·Upgrade 헤더
    private static volatile String lastRequestProtocol;
    private static volatile String lastRequestUpgradeHeader;

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

    private static final String CANCELED_JSON = """
            {
              "paymentKey": "pk_1",
              "orderId": "%s",
              "status": "PARTIAL_CANCELED",
              "totalAmount": 20000,
              "balanceAmount": 5000,
              "method": "카드",
              "approvedAt": "2026-10-12T15:00:00+09:00",
              "cancels": [
                {"cancelAmount": 15000, "cancelReason": "충전 취소", "canceledAt": "2026-10-13T10:00:00+09:00",
                 "transactionKey": "tx_1", "cancelStatus": "DONE"}
              ]
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
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.totalAmount()).isEqualTo(20_000);
        assertThat(response.method()).isEqualTo("카드");
        assertThat(response.approvedAtKst()).isEqualTo(LocalDateTime.of(2026, 10, 12, 15, 0));
    }

    @Test
    @DisplayName("취소: 결제키 경로로 취소 사유·금액과 Idempotency-Key 헤더를 보내고, 취소 내역을 받는다")
    void cancel_success() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk_1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "TOPUP-7-CANCEL-0"))
                .andExpect(jsonPath("$.cancelReason").value("충전 취소"))
                .andExpect(jsonPath("$.cancelAmount").value(15000))
                .andRespond(withSuccess(CANCELED_JSON, MediaType.APPLICATION_JSON));

        TossPaymentResponse response = client.cancel("pk_1", 15_000, "충전 취소", "TOPUP-7-CANCEL-0");

        server.verify();
        assertThat(response.status()).isEqualTo("PARTIAL_CANCELED");
        assertThat(response.canceledAmount()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("취소 내역: 성공(DONE)한 취소 금액만 더하고, 취소 내역이 없으면 0")
    void canceledAmount() {
        TossPaymentResponse canceled = new TossPaymentResponse("pk_1", ORDER_ID, "PARTIAL_CANCELED", 20_000, "카드", null,
                List.of(new TossPaymentResponse.Cancel(5_000, "DONE"),
                        new TossPaymentResponse.Cancel(3_000, "DONE"),
                        new TossPaymentResponse.Cancel(2_000, "FAILED")));
        TossPaymentResponse notCanceled = new TossPaymentResponse("pk_1", ORDER_ID, "DONE", 20_000, "카드", null, null);

        assertThat(canceled.canceledAmount()).isEqualTo(8_000);
        assertThat(notCanceled.canceledAmount()).isZero();
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
    @DisplayName("승인: 성공 응답을 해석하지 못하면(응답을 읽는 단계의 실패) TossTimeoutException")
    void confirm_unreadableSuccessResponse() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.confirm("pk_1", ORDER_ID, 20_000))
                .isInstanceOf(TossTimeoutException.class);
    }

    @Test
    @DisplayName("결제 조회: 결제키 경로로 결제 결과를 받는다 (주문번호 조회는 결제위젯 시크릿 키로 호출할 수 없음)")
    void getPayment_success() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PAYMENT_JSON, MediaType.APPLICATION_JSON));

        TossPaymentResponse response = client.getPayment("pk_1");

        server.verify();
        assertThat(response.paymentKey()).isEqualTo("pk_1");
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("결제 조회: 결제가 없으면 TossPaymentException (404)")
    void getPayment_notFound() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk_1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}"));

        assertThatThrownBy(() -> client.getPayment("pk_1"))
                .isInstanceOfSatisfying(TossPaymentException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND_PAYMENT");
                });
    }

    // ── 실제 HTTP 서버로 확인: MockRestServiceServer는 예외를 요청을 보내는 단계에서 던져서 실제 네트워크의 응답 대기 시간 초과·연결 끊김과 동작이 다름 ──

    @Test
    @DisplayName("실제 HTTP 서버: HTTP/1.1로 승인 요청을 보내고 응답을 결제 결과로 변환한다")
    void realServer_confirm_success() throws IOException {
        HttpServer httpServer = startHttpServer(Duration.ZERO);
        try {
            TossPaymentResponse response = realClient(httpServer.getAddress().getPort()).confirm("pk_1", ORDER_ID, 20_000);

            assertThat(lastRequestProtocol).isEqualTo("HTTP/1.1");
            assertThat(lastRequestUpgradeHeader).isNull();   // HTTP/2 업그레이드(h2c)를 시도하지 않음
            assertThat(response.orderId()).isEqualTo(ORDER_ID);
            assertThat(response.status()).isEqualTo("DONE");
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    @DisplayName("실제 HTTP 서버: 응답 대기 시간을 넘기면 TossTimeoutException (결과 모름)")
    void realServer_confirm_readTimeout() throws IOException {
        HttpServer httpServer = startHttpServer(Duration.ofSeconds(2));
        try {
            assertThatThrownBy(() -> realClient(httpServer.getAddress().getPort()).confirm("pk_1", ORDER_ID, 20_000))
                    .isInstanceOf(TossTimeoutException.class);
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    @DisplayName("실제 HTTP 서버: 응답 없이 연결이 끊기면 TossTimeoutException (결과 모름)")
    void realServer_confirm_connectionClosed() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // 요청을 받고 아무 응답 없이 연결을 닫는 서버
            Thread.ofVirtual().start(() -> {
                try (Socket socket = serverSocket.accept()) {
                    InputStream in = socket.getInputStream();
                    in.read(new byte[1024]);
                } catch (IOException ignored) {
                    // 테스트 종료 시 소켓이 닫히면 무시
                }
            });

            assertThatThrownBy(() -> realClient(serverSocket.getLocalPort()).confirm("pk_1", ORDER_ID, 20_000))
                    .isInstanceOf(TossTimeoutException.class);
        }
    }

    // 승인 요청에 delay만큼 기다렸다가 결제 결과를 돌려주는 실제 HTTP 서버
    private static HttpServer startHttpServer(Duration delay) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.createContext("/v1/payments/confirm", exchange -> {
            try {
                lastRequestProtocol = exchange.getProtocol();
                lastRequestUpgradeHeader = exchange.getRequestHeaders().getFirst("Upgrade");
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(delay);
                byte[] body = PAYMENT_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException | IOException ignored) {
                // 클라이언트가 먼저 끊으면 무시
            } finally {
                exchange.close();
            }
        });
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        return httpServer;
    }

    private static TossPaymentsClient realClient(int port) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(TossPaymentsClient.requestFactory(TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT));
        return new TossPaymentsClient(builder, "http://localhost:" + port, SECRET_KEY);
    }
}
