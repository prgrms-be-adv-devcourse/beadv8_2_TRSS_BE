package com.backend.boundedContext.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.shared.settlement.out.SettlementApi;

class MemberSettlementHttpAdapterTest {

    private static final String BASE_URL = "http://settlement:8084";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String URL = BASE_URL + "/internal/v1/settlements/sellers/5/unsettled-amount";

    private MockRestServiceServer server;
    private SettlementApi settlementApi;
    private MemberSettlementHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        settlementApi = mock(SettlementApi.class);
        adapter = new MemberSettlementHttpAdapter(
                new MemberInternalApiClient("settlement", builder, BASE_URL, INTERNAL_API_KEY), settlementApi);
    }

    @Test
    @DisplayName("미지급 금액을 판매자 ID로 조회한다. 요청에는 내부 API 키 헤더가 붙는다")
    void getUnsettledAmount() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData("12000"), MediaType.APPLICATION_JSON));

        assertThat(adapter.getUnsettledAmount(5L)).isEqualTo(12_000L);
        server.verify();
    }

    @Test
    @DisplayName("미지급 금액 data가 비어 있으면 0으로 통과시키지 않고 MemberPortUnavailableException을 던진다")
    void getUnsettledAmount_emptyData() {
        server.expect(requestTo(URL)).andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getUnsettledAmount(5L)).isInstanceOf(MemberPortUnavailableException.class);
    }

    @Test
    @DisplayName("정산 보류는 HTTP로 열려 있지 않아 같은 앱의 SettlementApi를 부른다 (#225에서 제거)")
    void holdSeller() {
        adapter.holdSeller(5L, "위조 상품 판매");

        verify(settlementApi).holdSeller(5L, "위조 상품 판매");
    }

    private static String rsData(String data) {
        return """
                {"resultCode": "200-1", "msg": "조회했습니다.", "data": %s}
                """.formatted(data);
    }
}
