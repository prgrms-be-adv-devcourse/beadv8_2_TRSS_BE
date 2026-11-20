package com.backend.boundedContext.payment.out.hecto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class HectoClientTest {

    private static final String BASE_URL = "https://tbgw.settlebank.co.kr";
    private static final String MCHT_ID = "test_mcht";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final String TRD_NO = "STFP_PGCAtest_mcht0261114220111M1522521";
    private static final LocalDateTime REQUEST_AT = LocalDateTime.of(2026, 11, 15, 10, 0, 5);
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 14, 23, 59, 30);

    private final HectoCrypto crypto = new HectoCrypto("0123456789abcdef0123456789abcdef", "TEST_HASH_KEY");
    private MockRestServiceServer server;
    private HectoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HectoClient(builder, BASE_URL, MCHT_ID, crypto);
    }

    @Test
    @DisplayName("승인: 요청 일시·금액 해시와 암호화한 금액, 인증 거래번호로 승인을 요청하고 응답 금액을 풀어 돌려준다")
    void approve() {
        server.expect(requestTo(BASE_URL + "/spay/APIService.do"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.params.mchtId").value(MCHT_ID))
                .andExpect(jsonPath("$.params.ver").value("0A19"))
                .andExpect(jsonPath("$.params.method").value("CA"))
                .andExpect(jsonPath("$.params.bizType").value("B2"))
                .andExpect(jsonPath("$.params.mchtTrdNo").value(ORDER_ID))
                .andExpect(jsonPath("$.params.trdDt").value("20261115"))
                .andExpect(jsonPath("$.params.trdTm").value("100005"))
                .andExpect(jsonPath("$.data.pktHash").value(crypto.hash("20261115", "100005", MCHT_ID, ORDER_ID, "20000")))
                .andExpect(jsonPath("$.data.trdAmt").value(crypto.encryptAmount(20_000)))
                .andExpect(jsonPath("$.data.authTrdNo").value(TRD_NO))
                .andRespond(withSuccess(approveJson(crypto.hash("0021", "20261115", "100007", MCHT_ID, ORDER_ID, "20000")), MediaType.APPLICATION_JSON));

        HectoResponse response = client.approve(ORDER_ID, TRD_NO, 20_000, REQUEST_AT);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.outRsltCd()).isEqualTo("0000");
        assertThat(response.trdNo()).isEqualTo(TRD_NO);
        assertThat(response.amount()).isEqualTo(20_000);
        assertThat(response.transactedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 10, 0, 7));
        server.verify();
    }

    @Test
    @DisplayName("조회: 헥토가 JSON 본문을 text/plain·*/* Content-Type으로 보내도 읽는다")
    void inquiry_nonJsonContentType() {
        String json = """
                {"params": {"mchtId": "%s", "mchtTrdNo": "INQ-1", "trdNo": "", "trdDt": "20261116", "trdTm": "143817",
                            "outStatCd": "0031", "outRsltCd": "ST06", "outRsltMsg": "거래번호 정보가 존재하지 않습니다."},
                 "data": {"pktHash": "x", "method": "CA", "trdAmt": "10000", "cnclAmt": "0"}}
                """.formatted(MCHT_ID);
        server.expect(requestTo(BASE_URL + "/spay/APITrdcheck.do"))
                .andRespond(withSuccess(json, MediaType.parseMediaType("text/plain;charset=UTF-8")));
        server.expect(requestTo(BASE_URL + "/spay/APITrdcheck.do"))
                .andRespond(withSuccess().body(json).header("Content-Type", "*/*;charset=UTF-8"));

        assertThat(client.inquiry(ORDER_ID, TRD_NO, 10_000, REQUESTED_AT, REQUEST_AT).outRsltCd()).isEqualTo("ST06");
        assertThat(client.inquiry(ORDER_ID, TRD_NO, 10_000, REQUESTED_AT, REQUEST_AT).outRsltCd()).isEqualTo("ST06");
        server.verify();
    }

    @Test
    @DisplayName("JSON이 아닌 본문(장애 페이지 등)은 HectoResultUnknownException")
    void notJsonBody() {
        server.expect(requestTo(BASE_URL + "/spay/APIService.do"))
                .andRespond(withSuccess("<html>error</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.approve(ORDER_ID, TRD_NO, 20_000, REQUEST_AT))
                .isInstanceOf(HectoResultUnknownException.class);
    }

    @Test
    @DisplayName("승인: 성공 응답의 해시가 맞지 않으면 승인됐는지 알 수 없어 HectoResultUnknownException")
    void approve_hashMismatch() {
        server.expect(requestTo(BASE_URL + "/spay/APIService.do"))
                .andRespond(withSuccess(approveJson("0".repeat(64)), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.approve(ORDER_ID, TRD_NO, 20_000, REQUEST_AT))
                .isInstanceOf(HectoResultUnknownException.class);
    }

    @Test
    @DisplayName("승인: 실패 응답(0031)은 예외 없이 결과 코드를 담아 돌려준다")
    void approve_failed() {
        server.expect(requestTo(BASE_URL + "/spay/APIService.do"))
                .andRespond(withSuccess(failJson("ST09", "거래금액이 유효하지 않습니다."), MediaType.APPLICATION_JSON));

        HectoResponse response = client.approve(ORDER_ID, TRD_NO, 20_000, REQUEST_AT);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.outRsltCd()).isEqualTo("ST09");
        assertThat(response.amount()).isNull();
    }

    @Test
    @DisplayName("취소: 취소 회차를 세 자리로, 취소 금액을 암호화해 요청하고 취소 금액·남은 금액을 풀어 돌려준다")
    void cancel() {
        server.expect(requestTo(BASE_URL + "/spay/APICancel.do"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.params.bizType").value("C0"))
                .andExpect(jsonPath("$.params.ver").value("0A19"))
                .andExpect(jsonPath("$.params.mchtTrdNo").value(ORDER_ID))
                .andExpect(jsonPath("$.data.pktHash").value(crypto.hash("20261115", "100005", MCHT_ID, ORDER_ID, "300")))
                .andExpect(jsonPath("$.data.orgTrdNo").value(TRD_NO))
                .andExpect(jsonPath("$.data.crcCd").value("KRW"))
                .andExpect(jsonPath("$.data.cnclOrd").value("002"))
                .andExpect(jsonPath("$.data.cnclAmt").value(crypto.encryptAmount(300)))
                .andRespond(withSuccess("""
                        {"params": {"mchtId": "%s", "mchtTrdNo": "%s", "trdNo": "STFP_CANCEL_1", "trdDt": "20261115", "trdTm": "100006",
                                    "outStatCd": "0021", "outRsltCd": "0000", "outRsltMsg": "정상처리되었습니다"},
                         "data": {"pktHash": "x", "orgTrdNo": "%s", "cnclAmt": "%s", "blcAmt": "%s"}}
                        """.formatted(MCHT_ID, ORDER_ID, TRD_NO, crypto.encryptAmount(300), crypto.encryptAmount(19_700)), MediaType.APPLICATION_JSON));

        HectoResponse response = client.cancel(ORDER_ID, TRD_NO, 2, 300, REQUEST_AT);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.canceledAmount()).isEqualTo(300);
        assertThat(response.balanceAmount()).isEqualTo(19_700);
        server.verify();
    }

    @Test
    @DisplayName("취소: 남은 금액보다 큰 취소(ST57)는 실패 응답으로 돌려준다")
    void cancel_overBalance() {
        server.expect(requestTo(BASE_URL + "/spay/APICancel.do"))
                .andRespond(withSuccess(failJson("ST57", "취소금액(환불금액)이 원거래금액을 초과합니다."), MediaType.APPLICATION_JSON));

        HectoResponse response = client.cancel(ORDER_ID, TRD_NO, 1, 30_000, REQUEST_AT);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.outRsltCd()).isEqualTo("ST57");
    }

    @Test
    @DisplayName("조회: 새 조회 주문번호로, 충전 주문번호·원거래 일자·평문 금액을 보내고 누적 취소 금액을 돌려준다")
    void inquiry() {
        server.expect(requestTo(BASE_URL + "/spay/APITrdcheck.do"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.params.ver").value("0A1M"))
                .andExpect(jsonPath("$.params.mchtTrdNo").value(startsWith("INQ-")))
                .andExpect(jsonPath("$.data.method").value("CA"))
                .andExpect(jsonPath("$.data.trdAmt").value("20000"))
                .andExpect(jsonPath("$.data.orgTrdDt").value("20261114"))
                .andExpect(jsonPath("$.data.orgMchtTrdNo").value(ORDER_ID))
                .andExpect(jsonPath("$.data.orgTrdNo").value(TRD_NO))
                .andRespond(withSuccess("""
                        {"params": {"mchtId": "%s", "mchtTrdNo": "INQ-1", "trdNo": "%s", "trdDt": "20261115", "trdTm": "100006",
                                    "outStatCd": "0021", "outRsltCd": "0001", "outRsltMsg": "취소상태"},
                         "data": {"pktHash": "x", "method": "CA", "trdAmt": "20000", "cnclAmt": "5000"}}
                        """.formatted(MCHT_ID, TRD_NO), MediaType.APPLICATION_JSON));

        HectoResponse response = client.inquiry(ORDER_ID, TRD_NO, 20_000, REQUESTED_AT, REQUEST_AT);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.outRsltCd()).isEqualTo("0001");
        assertThat(response.amount()).isEqualTo(20_000);
        assertThat(response.canceledAmount()).isEqualTo(5_000);
        server.verify();
    }

    @Test
    @DisplayName("조회: 해시는 조회 주문번호로 만들고, 거래번호가 없으면 빈 값으로 보낸다")
    void inquiry_hashAndNoTrdNo() {
        server.expect(requestTo(BASE_URL + "/spay/APITrdcheck.do"))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    String inquiryOrderId = body.replaceAll(".*\"mchtTrdNo\":\"(INQ-[^\"]+)\".*", "$1");
                    assertThat(body).contains("\"pktHash\":\"" + crypto.hash("20261115", "100005", MCHT_ID, inquiryOrderId, "20000") + "\"");
                })
                .andExpect(jsonPath("$.data.orgTrdNo").value(""))
                .andRespond(withSuccess(failJson("ST06", "거래번호 정보가 존재하지 않습니다."), MediaType.APPLICATION_JSON));

        HectoResponse response = client.inquiry(ORDER_ID, null, 20_000, REQUESTED_AT, REQUEST_AT);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.outRsltCd()).isEqualTo("ST06");
        server.verify();
    }

    @Test
    @DisplayName("응답 시간 초과는 HectoResultUnknownException")
    void timeout() {
        server.expect(requestTo(BASE_URL + "/spay/APIService.do"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.approve(ORDER_ID, TRD_NO, 20_000, REQUEST_AT))
                .isInstanceOf(HectoResultUnknownException.class);
    }

    @Test
    @DisplayName("HTTP 5xx 응답은 HectoResultUnknownException")
    void serverError() {
        server.expect(requestTo(BASE_URL + "/spay/APICancel.do"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.cancel(ORDER_ID, TRD_NO, 1, 300, REQUEST_AT))
                .isInstanceOf(HectoResultUnknownException.class);
    }

    @Test
    @DisplayName("헥토 형식이 아닌 응답은 HectoResultUnknownException")
    void unreadableResponse() {
        server.expect(requestTo(BASE_URL + "/spay/APITrdcheck.do"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.inquiry(ORDER_ID, TRD_NO, 20_000, REQUESTED_AT, REQUEST_AT))
                .isInstanceOf(HectoResultUnknownException.class);
    }

    @Test
    @DisplayName("실제 HTTP 서버: 조회는 조회 전용 응답 대기 시간이 짧아 결과 모름, 같은 지연에도 승인은 응답을 받는다")
    void realServer_lookupUsesShortReadTimeout() throws IOException {
        HttpServer httpServer = startHttpServer(Duration.ofMillis(700));
        try {
            RestClient.Builder builder = RestClient.builder().requestFactory(HectoClient.requestFactory(Duration.ofSeconds(1), Duration.ofMillis(1_500)));
            RestClient.Builder lookupBuilder = RestClient.builder().requestFactory(HectoClient.requestFactory(Duration.ofSeconds(1), Duration.ofMillis(300)));
            HectoClient realClient = new HectoClient(builder, lookupBuilder, "http://localhost:" + httpServer.getAddress().getPort(), MCHT_ID, crypto);

            assertThatThrownBy(() -> realClient.inquiry(ORDER_ID, TRD_NO, 20_000, REQUESTED_AT, REQUEST_AT))
                    .isInstanceOf(HectoResultUnknownException.class);
            assertThat(realClient.approve(ORDER_ID, TRD_NO, 20_000, REQUEST_AT).outRsltCd()).isEqualTo("ST09");
        } finally {
            httpServer.stop(0);
        }
    }

    // 모든 요청에 delay만큼 기다렸다가 실패 응답(0031)을 돌려주는 실제 HTTP 서버. 실패 응답은 해시를 검증하지 않아 승인 응답으로 쓸 수 있음
    private static HttpServer startHttpServer(Duration delay) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.createContext("/spay", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(delay);
                byte[] body = failJson("ST09", "거래금액이 유효하지 않습니다.").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
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

    private String approveJson(String pktHash) {
        return """
                {"params": {"mchtId": "%s", "ver": "0A19", "method": "CA", "bizType": "B2", "encCd": "23", "mchtTrdNo": "%s",
                            "trdNo": "%s", "trdDt": "20261115", "trdTm": "100007", "outStatCd": "0021", "outRsltCd": "0000", "outRsltMsg": "정상처리되었습니다"},
                 "data": {"pktHash": "%s", "trdAmt": "%s", "apprNo": "99307312", "fnNm": "하나", "cardNo": "x"}}
                """.formatted(MCHT_ID, ORDER_ID, TRD_NO, pktHash, crypto.encryptAmount(20_000));
    }

    private static String failJson(String code, String message) {
        return """
                {"params": {"mchtId": "%s", "mchtTrdNo": "%s", "trdNo": "", "trdDt": "20261115", "trdTm": "100006",
                            "outStatCd": "0031", "outRsltCd": "%s", "outRsltMsg": "%s"},
                 "data": {"pktHash": "x", "trdAmt": "", "cnclAmt": null, "blcAmt": ""}}
                """.formatted(MCHT_ID, ORDER_ID, code, message);
    }
}
