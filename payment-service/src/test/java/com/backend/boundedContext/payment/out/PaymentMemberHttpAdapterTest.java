package com.backend.boundedContext.payment.out;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

/**
 * 회원 내부 API를 부르는 요청 모양과, 응답·오류를 Port 결과로 바꾸는지 확인한다.
 * 실제 회원 서비스 대신 MockRestServiceServer가 정해 둔 응답을 돌려준다.
 */
class PaymentMemberHttpAdapterTest {

    private static final String BASE_URL = "http://member-service:8081";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private static final String MEMBER_JSON = """
            {"id": 3, "email": "buyer@palette.test", "name": "구매자", "role": "USER", "status": "ACTIVE"}
            """;

    private MockRestServiceServer server;
    private PaymentMemberHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new PaymentMemberHttpAdapter(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("활성 회원 확인: GET /internal/v1/members/{id}/active가 200이면 예외 없이 끝난다")
    void checkActiveMember_success() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/3/active"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(InternalApiKeyFilter.HEADER, INTERNAL_API_KEY))   // 내부 키 헤더를 함께 보낸다
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "활성 회원입니다.", "data": null}
                        """, MediaType.APPLICATION_JSON));

        adapter.checkActiveMember(3L);

        server.verify();
    }

    @Test
    @DisplayName("활성 회원 확인: 회원 서비스가 401-UNAUTHORIZED로 응답하면 같은 resultCode의 DomainException")
    void checkActiveMember_unauthorized() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/3/active"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"resultCode": "401-UNAUTHORIZED", "msg": "로그인이 필요합니다.", "data": null}
                                """));

        assertThatThrownBy(() -> adapter.checkActiveMember(3L))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("401-UNAUTHORIZED");
                    assertThat(e.getMsg()).isEqualTo("로그인이 필요합니다.");
                    assertThat(e.statusCode()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("여러 회원 조회: ids로 보내고 RsData.data의 목록을 돌려준다")
    void getMembers() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members?ids=3&ids=4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "회원 목록을 조회했습니다.", "data": [%s]}
                        """.formatted(MEMBER_JSON), MediaType.APPLICATION_JSON));

        List<MemberDto> members = adapter.getMembers(List.of(3L, 4L));

        assertThat(members).containsExactly(
                new MemberDto(3L, "buyer@palette.test", "구매자", MemberRole.USER, MemberStatus.ACTIVE));
    }

    @Test
    @DisplayName("여러 회원 조회: ID가 없으면 회원 서비스를 부르지 않고 빈 목록")
    void getMembers_empty() {
        assertThat(adapter.getMembers(List.of())).isEmpty();

        server.verify();   // 기대한 요청이 없으므로, 요청이 하나라도 가면 실패
    }

    @Test
    @DisplayName("이메일 조회: 본문에 email을 담아 POST하고, 회원이 있으면 Optional로 돌려준다")
    void findMemberByEmail_found() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/search-by-email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value("buyer@palette.test"))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "이메일로 회원을 조회했습니다.", "data": %s}
                        """.formatted(MEMBER_JSON), MediaType.APPLICATION_JSON));

        assertThat(adapter.findMemberByEmail("buyer@palette.test"))
                .hasValueSatisfying(member -> assertThat(member.id()).isEqualTo(3L));
    }

    @Test
    @DisplayName("이메일 조회: data가 null이면 empty")
    void findMemberByEmail_notFound() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/search-by-email"))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "이메일로 회원을 조회했습니다.", "data": null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.findMemberByEmail("nobody@palette.test")).isEmpty();
    }

    @Test
    @DisplayName("응답이 RsData가 아니면(예: 게이트웨이 HTML 오류) 502 EXTERNAL_SERVICE_ERROR")
    void notRsDataError() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/3/active"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>502 Bad Gateway</html>"));

        assertThatThrownBy(() -> adapter.checkActiveMember(3L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    @Test
    @DisplayName("회원 서비스에 닿지 못하면(시간 초과) 502 EXTERNAL_SERVICE_ERROR")
    void timeout() {
        server.expect(requestTo(BASE_URL + "/internal/v1/members/3/active"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> adapter.checkActiveMember(3L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }
}
