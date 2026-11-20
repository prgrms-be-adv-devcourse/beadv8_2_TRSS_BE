package com.backend.settlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
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
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;

/**
 * 정산의 회원 내부 API 호출: 응답 data 꺼내기, 내부 키 헤더, 오류 응답과 응답 없음 처리
 */
class MemberApiClientTest {

    private static final String BASE_URL = "http://member:8081";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String SELLER_JSON = """
            {"sellerId": 3, "memberId": 7, "businessName": "팔레트 뷰티", "status": "SANCTIONED"}
            """;

    private MockRestServiceServer server;
    private MemberApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MemberApiClient(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("판매자 1명을 조회하고, 요청에는 내부 API 키 헤더가 붙는다")
    void getSeller() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData(SELLER_JSON), MediaType.APPLICATION_JSON));

        SellerDto seller = client.getSeller(3L);

        assertThat(seller.memberId()).isEqualTo(7L);
        assertThat(seller.status()).isEqualTo(SellerStatus.SANCTIONED);
        server.verify();
    }

    @Test
    @DisplayName("판매자 여러 명을 ids로 한 번에 조회한다. 빈 목록이면 호출하지 않는다")
    void getSellers() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers?ids=3&ids=4"))
                .andRespond(withSuccess(rsData("[" + SELLER_JSON + "]"), MediaType.APPLICATION_JSON));

        List<SellerDto> sellers = client.getSellers(List.of(3L, 4L));

        assertThat(sellers).hasSize(1);
        assertThat(client.getSellers(List.of())).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("판매자 신청 이력이 없으면(data null) empty")
    void findSellerByMemberId_none() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/by-member/9"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThat(client.findSellerByMemberId(9L)).isEmpty();
    }

    @Test
    @DisplayName("회원 서비스의 4xx 오류는 같은 resultCode와 메시지로 던진다")
    void error4xx() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/3"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"resultCode": "404-SELLER_NOT_FOUND", "msg": "판매자를 찾을 수 없습니다.", "data": null}
                        """));

        assertThatThrownBy(() -> client.getSeller(3L))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("404-SELLER_NOT_FOUND");
                    assertThat(e.statusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("응답을 받지 못하면(시간 초과) 502 EXTERNAL_SERVICE_ERROR")
    void noResponse() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/3"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.getSeller(3L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    private static String rsData(String dataJson) {
        return "{\"resultCode\": \"200-1\", \"msg\": \"ok\", \"data\": " + dataJson + "}";
    }
}
