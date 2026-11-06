package com.backend.boundedContext.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.global.rsData.RsData;

class MemberInternalApiClientTest {

    private static final String BASE_URL = "http://market:8082";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String URL = BASE_URL + "/internal/v1/orders/active/exists?buyerId=3";

    private MockRestServiceServer server;
    private MemberInternalApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MemberInternalApiClient("order", builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("응답 RsData의 data를 꺼내 돌려준다. 요청에는 내부 API 키 헤더가 붙는다")
    void call_success() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "조회했습니다.", "data": true}
                        """, MediaType.APPLICATION_JSON));

        assertThat(hasActiveOrders()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("상대가 4xx 오류로 응답하면 같은 resultCode·메시지의 DomainException을 던진다")
    void call_clientError() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "404-WALLET_NOT_FOUND", "msg": "지갑을 찾을 수 없습니다.", "data": null}
                        """));

        assertThatThrownBy(this::hasActiveOrders)
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("404-WALLET_NOT_FOUND");
                    assertThat(e.getMsg()).isEqualTo("지갑을 찾을 수 없습니다.");
                    assertThat(e.statusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("상대가 5xx로 응답하면 확인하지 못한 것으로 보고 MemberPortUnavailableException을 던진다")
    void call_serverError() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "500-INTERNAL_ERROR", "msg": "일시적인 오류가 발생했습니다.", "data": null}
                        """));

        assertThatThrownBy(this::hasActiveOrders).isInstanceOf(MemberPortUnavailableException.class);
    }

    @Test
    @DisplayName("4xx라도 본문이 RsData가 아니면 MemberPortUnavailableException을 던진다")
    void call_unreadableError() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<html>Not Found</html>"));

        assertThatThrownBy(this::hasActiveOrders).isInstanceOf(MemberPortUnavailableException.class);
    }

    @Test
    @DisplayName("응답을 받지 못하면(시간 초과) MemberPortUnavailableException을 던진다")
    void call_timeout() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(this::hasActiveOrders).isInstanceOf(MemberPortUnavailableException.class);
    }

    private Boolean hasActiveOrders() {
        return client.call(() -> client.restClient().get()
                .uri(uri -> uri.path("/internal/v1/orders/active/exists").queryParam("buyerId", 3L).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Boolean>>() {}));
    }
}
