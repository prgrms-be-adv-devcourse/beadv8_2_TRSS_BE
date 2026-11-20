package com.backend.settlement.client;

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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.SettledOrderAmountDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;

/**
 * 정산의 결제 내부 API 호출: 지급, 지급 내역 조회, 그리고 지급 결과 구분(4xx 거절 / 5xx, 응답 없음 = 결과 모름)
 */
class PaymentApiClientTest {

    private static final String BASE_URL = "http://payment:8083";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String TRANSFER_JSON = """
            {"idempotencyKey": "SETTLEMENT-31", "walletTransactionId": 501, "payoutAmount": 18000,
             "feeAmount": 2000, "transferredAt": "2026-11-10T00:12:00"}
            """;

    private MockRestServiceServer server;
    private PaymentApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PaymentApiClient(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("지급 요청을 JSON으로 보내고 지급 결과를 받는다")
    void transfer() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(jsonPath("$.idempotencyKey").value("SETTLEMENT-31"))
                .andExpect(jsonPath("$.payoutAmount").value(18000))
                .andRespond(withSuccess(rsData(TRANSFER_JSON), MediaType.APPLICATION_JSON));

        TransferResultDto result = client.transfer(command());

        assertThat(result.walletTransactionId()).isEqualTo(501L);
        assertThat(result.payoutAmount()).isEqualTo(18_000L);
        server.verify();
    }

    @Test
    @DisplayName("지급 내역이 있으면 결과, 없으면(data null) empty")
    void findTransfer() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers/SETTLEMENT-31"))
                .andRespond(withSuccess(rsData(TRANSFER_JSON), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers/SETTLEMENT-32"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        Optional<TransferResultDto> found = client.findTransfer("SETTLEMENT-31");
        Optional<TransferResultDto> none = client.findTransfer("SETTLEMENT-32");

        assertThat(found).map(TransferResultDto::walletTransactionId).contains(501L);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("결제가 거절한 4xx(홀딩 부족)는 같은 resultCode의 4xx 예외 → 정산은 실패로 기록한다")
    void transfer_rejected4xx() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "409-HOLDING_INSUFFICIENT", "msg": "홀딩의 남은 금액이 지급액보다 적습니다.", "data": null}
                        """));

        assertThatThrownBy(() -> client.transfer(command()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("409-HOLDING_INSUFFICIENT");
                    assertThat(e.statusCode()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("결제의 5xx는 5xx 예외 → 정산은 결과 모름(PROCESSING)으로 둔다")
    void transfer_5xx() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "500-INTERNAL_ERROR", "msg": "서버 오류", "data": null}
                        """));

        assertThatThrownBy(() -> client.transfer(command()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.statusCode()).isEqualTo(500));
    }

    @Test
    @DisplayName("응답을 받지 못하면(시간 초과) 502 → 정산은 결과 모름(PROCESSING)으로 둔다")
    void transfer_noResponse() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.transfer(command()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR");
                    assertThat(e.statusCode()).isEqualTo(502);
                });
    }

    @Test
    @DisplayName("2xx인데 지급 결과가 없으면(data null, 빈 본문) 502 → 정산은 결과 모름(PROCESSING)으로 둔다")
    void transfer_emptyResult() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.transfer(command()))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR");
                        assertThat(e.statusCode()).isEqualTo(502);
                    });
        }
        server.verify();
    }

    private static SettlementTransferCommand command() {
        return new SettlementTransferCommand("SETTLEMENT-31", 31L, 7L, 18_000L, 2_000L,
                List.of(new SettledOrderAmountDto(100L, 20_000L)));
    }

    private static String rsData(String dataJson) {
        return "{\"resultCode\": \"200-1\", \"msg\": \"ok\", \"data\": " + dataJson + "}";
    }
}
