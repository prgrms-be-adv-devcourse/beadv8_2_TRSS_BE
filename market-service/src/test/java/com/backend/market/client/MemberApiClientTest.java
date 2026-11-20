package com.backend.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
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

class MemberApiClientTest {

    private static final String BASE_URL = "http://member:8080";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private static final String SELLER_JSON = """
            {"sellerId": 3, "memberId": 7, "businessName": "팔레트 뷰티", "status": "APPROVED"}
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
    @DisplayName("활성 회원이면 예외 없이 끝난다. 요청에는 내부 API 키 헤더가 붙는다")
    void checkActiveMember_active() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/7/active"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        client.checkActiveMember(7L);

        server.verify();
    }

    @Test
    @DisplayName("회원 서비스가 401로 응답하면 같은 resultCode·메시지로 예외를 던진다")
    void checkActiveMember_inactive() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/7/active"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"resultCode": "401-UNAUTHORIZED", "msg": "로그인이 필요합니다.", "data": null}
                                """));

        assertThatThrownBy(() -> client.checkActiveMember(7L))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("401-UNAUTHORIZED");
                    assertThat(e.getMsg()).isEqualTo("로그인이 필요합니다.");
                    assertThat(e.statusCode()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("판매자 신청 정보가 있으면 담아서, data가 null이면 empty로 돌려준다")
    void findSellerByMemberId() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/by-member/7"))
                .andRespond(withSuccess(rsData(SELLER_JSON), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/by-member/8"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThat(client.findSellerByMemberId(7L))
                .contains(new SellerDto(3L, 7L, "팔레트 뷰티", SellerStatus.APPROVED));
        assertThat(client.findSellerByMemberId(8L)).isEmpty();
    }

    @Test
    @DisplayName("승인되지 않은 판매자면 403-SELLER_NOT_APPROVED를 그대로 던진다")
    void getApprovedSellerByMemberId_notApproved() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/by-member/7/approved"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"resultCode": "403-SELLER_NOT_APPROVED", "msg": "판매 기능을 사용할 수 없는 판매자입니다.", "data": null}
                                """));

        assertThatThrownBy(() -> client.getApprovedSellerByMemberId(7L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("403-SELLER_NOT_APPROVED"));
    }

    @Test
    @DisplayName("여러 판매자를 ids로 한 번에 조회한다")
    void getSellers() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers?ids=3&ids=4"))
                .andRespond(withSuccess(rsData("[" + SELLER_JSON + "]"), MediaType.APPLICATION_JSON));

        List<SellerDto> sellers = client.getSellers(List.of(3L, 4L));

        assertThat(sellers).extracting(SellerDto::sellerId).containsExactly(3L);
    }

    @Test
    @DisplayName("조회할 판매자가 없으면 호출하지 않고 빈 목록을 돌려준다")
    void getSellers_empty() {
        server.expect(never(), anything());

        assertThat(client.getSellers(List.of())).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("이메일 조회는 본문에 이메일을 담아 보낸다")
    void findMemberByEmail() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/search-by-email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value("a@palette.com"))
                .andRespond(withSuccess(rsData("null"), MediaType.APPLICATION_JSON));

        assertThat(client.findMemberByEmail("a@palette.com")).isEmpty();
    }

    @Test
    @DisplayName("응답을 받지 못하면 502 EXTERNAL_SERVICE_ERROR")
    void noResponse() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/7/active"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.checkActiveMember(7L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    @Test
    @DisplayName("오류 본문이 RsData 형식이 아니면 502 EXTERNAL_SERVICE_ERROR")
    void unreadableError() {
        server.expect(requestTo(BASE_URL + "/internal/v1/sellers/3"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>Bad Gateway</html>"));

        assertThatThrownBy(() -> client.getSeller(3L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    private static String rsData(String data) {
        return """
                {"resultCode": "200-1", "msg": "ok", "data": %s}
                """.formatted(data);
    }
}
