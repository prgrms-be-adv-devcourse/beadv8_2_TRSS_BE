package com.backend.boundedContext.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.OrderSummaryDto;

class MemberOrderHttpAdapterTest {

    private static final String BASE_URL = "http://market:8082";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private MockRestServiceServer server;
    private MemberOrderHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new MemberOrderHttpAdapter(new MemberInternalApiClient("order", builder, BASE_URL, INTERNAL_API_KEY));
    }

    @Test
    @DisplayName("진행 중인 주문 여부를 구매자 ID로 조회한다. 요청에는 내부 API 키 헤더가 붙는다")
    void hasActiveOrders() {
        server.expect(requestTo(BASE_URL + "/internal/v1/orders/active/exists?buyerId=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData("true"), MediaType.APPLICATION_JSON));

        assertThat(adapter.hasActiveOrders(3L)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("진행 중인 판매 여부를 판매자 ID로 조회한다")
    void hasActiveSales() {
        server.expect(requestTo(BASE_URL + "/internal/v1/orders/active-sales/exists?sellerId=5"))
                .andRespond(withSuccess(rsData("false"), MediaType.APPLICATION_JSON));

        assertThat(adapter.hasActiveSales(5L)).isFalse();
    }

    @Test
    @DisplayName("최근 주문 목록을 구매자 ID와 개수로 조회한다")
    void getRecentOrders() {
        server.expect(requestTo(BASE_URL + "/internal/v1/orders/recent?buyerId=3&limit=5"))
                .andRespond(withSuccess(rsData("""
                        [{"orderId": 11, "status": "PAID", "totalAmount": 25000, "orderedAt": "2026-11-04T10:00:00"}]
                        """), MediaType.APPLICATION_JSON));

        assertThat(adapter.getRecentOrders(3L, 5))
                .containsExactly(new OrderSummaryDto(11L, OrderStatus.PAID, 25_000, LocalDateTime.of(2026, 11, 4, 10, 0)));
    }

    @Test
    @DisplayName("판단에 쓰는 여부 조회의 data가 비어 있으면 false로 통과시키지 않고 MemberPortUnavailableException을 던진다")
    void hasActiveOrders_emptyData() {
        server.expect(requestTo(BASE_URL + "/internal/v1/orders/active/exists?buyerId=3"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.hasActiveOrders(3L)).isInstanceOf(MemberPortUnavailableException.class);
    }

    private static String rsData(String data) {
        return """
                {"resultCode": "200-1", "msg": "조회했습니다.", "data": %s}
                """.formatted(data.strip());
    }
}
