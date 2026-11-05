package com.backend.boundedContext.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.WalletSummaryDto;

class MemberPaymentHttpAdapterTest {

    private static final String BASE_URL = "http://payment:8080";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private MockRestServiceServer server;
    private MemberPaymentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new MemberPaymentHttpAdapter(new MemberInternalApiClient("payment", builder, BASE_URL, INTERNAL_API_KEY));
    }

    @Test
    @DisplayName("지갑 잔액을 회원 ID로 조회한다. 요청에는 내부 API 키 헤더가 붙는다")
    void getWallet() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData("""
                        {"memberId": 3, "balance": 50000, "heldAmount": 1000}
                        """), MediaType.APPLICATION_JSON));

        assertThat(adapter.getWallet(3L)).isEqualTo(new WalletSummaryDto(3L, 50_000L, 1_000L));
        server.verify();
    }

    @Test
    @DisplayName("지갑이 없으면 결제의 404-WALLET_NOT_FOUND를 같은 resultCode로 던진다")
    void getWallet_notFound() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "404-WALLET_NOT_FOUND", "msg": "지갑을 찾을 수 없습니다.", "data": null}
                        """));

        assertThatThrownBy(() -> adapter.getWallet(3L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("404-WALLET_NOT_FOUND"));
    }

    @Test
    @DisplayName("결과 대기 중인 충전 건 여부를 회원 ID로 조회한다")
    void hasPendingTopups() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3/pending-topups"))
                .andRespond(withSuccess(rsData("true"), MediaType.APPLICATION_JSON));

        assertThat(adapter.hasPendingTopups(3L)).isTrue();
    }

    @Test
    @DisplayName("판단에 쓰는 충전 건 여부의 data가 비어 있으면 false로 통과시키지 않고 MemberPortUnavailableException을 던진다")
    void hasPendingTopups_emptyData() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3/pending-topups"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.hasPendingTopups(3L)).isInstanceOf(MemberPortUnavailableException.class);
    }

    private static String rsData(String data) {
        return """
                {"resultCode": "200-1", "msg": "조회했습니다.", "data": %s}
                """.formatted(data.strip());
    }
}
