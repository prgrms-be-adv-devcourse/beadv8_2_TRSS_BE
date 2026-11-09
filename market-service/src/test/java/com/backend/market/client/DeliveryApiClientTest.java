package com.backend.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
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
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;

class DeliveryApiClientTest {

    private static final String BASE_URL = "http://delivery:8085";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String URL = BASE_URL + "/internal/v1/deliveries?orderId=500";

    private MockRestServiceServer server;
    private DeliveryApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeliveryApiClient(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("배송 건이 있으면 data를 돌려준다. 요청에는 내부 API 키 헤더가 붙는다")
    void found() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData("""
                        {"deliveryId": 70, "status": "SHIPPED", "carrierName": "CJ대한통운", "trackingNumber": "123456789012",
                         "shippedAt": "2026-11-02T09:00:00", "deliveredAt": null}
                        """), MediaType.APPLICATION_JSON));

        Optional<DeliveryDto> result = client.findByOrderId(500L);

        server.verify();
        assertThat(result).contains(new DeliveryDto(70L, DeliveryStatus.SHIPPED, "CJ대한통운", "123456789012",
                LocalDateTime.of(2026, 11, 2, 9, 0), null));
    }

    @Test
    @DisplayName("배송 건이 아직 없으면(200 + data null) empty")
    void notYet() {
        server.expect(requestTo(URL)).andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThat(client.findByOrderId(500L)).isEmpty();
    }

    @Test
    @DisplayName("접속하지 못하면 502 EXTERNAL_SERVICE_ERROR")
    void connectFailed() {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));

        assertExternalServiceError();
    }

    @Test
    @DisplayName("응답 대기 시간이 지나면 502 EXTERNAL_SERVICE_ERROR")
    void readTimeout() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertExternalServiceError();
    }

    @Test
    @DisplayName("delivery가 5xx로 응답하면 502 EXTERNAL_SERVICE_ERROR")
    void serverError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"resultCode": "500-INTERNAL_SERVER_ERROR", "msg": "서버 오류", "data": null}
                        """));

        assertExternalServiceError();
    }

    @Test
    @DisplayName("내부 API 키가 맞지 않아 401로 응답해도 사용자 인증 오류가 아니라 502 EXTERNAL_SERVICE_ERROR")
    void unauthorized() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"resultCode": "401-INTERNAL_API_KEY_INVALID", "msg": "내부 API 키가 올바르지 않습니다.", "data": null}
                        """));

        assertExternalServiceError();
    }

    private void assertExternalServiceError() {
        assertThatThrownBy(() -> client.findByOrderId(500L))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR");
                    assertThat(e.statusCode()).isEqualTo(502);
                });
    }

    private static String rsData(String dataJson) {
        return """
                {"resultCode": "200-1", "msg": "배송 정보를 조회했습니다.", "data": %s}
                """.formatted(dataJson);
    }
}
