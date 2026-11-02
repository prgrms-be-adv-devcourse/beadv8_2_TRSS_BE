package com.backend.boundedContext.member.out.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;

class KakaoOAuthClientTest {

    private static final String AUTH_BASE_URL = "https://kauth.kakao.com";
    private static final String API_BASE_URL = "https://kapi.kakao.com";
    private static final String CLIENT_ID = "rest-api-key";
    private static final String CLIENT_SECRET = "client-secret";
    private static final String REDIRECT_URI = "http://localhost:5173/oauth/kakao/callback";

    private static final String TOKEN_JSON = """
            {"token_type":"bearer","access_token":"kakao-access-token","expires_in":21599,"refresh_token":"kakao-refresh-token"}
            """;
    private static final String USER_JSON = """
            {"id":4011223344,"connected_at":"2026-11-02T01:00:00Z","properties":{"nickname":"홍길동"}}
            """;

    private MockRestServiceServer server;

    private KakaoOAuthClient client(String clientSecret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new KakaoOAuthClient(builder, AUTH_BASE_URL, API_BASE_URL, CLIENT_ID, clientSecret, REDIRECT_URI);
    }

    @Test
    @DisplayName("인가 코드로 카카오 토큰을 받고, 그 토큰으로 회원번호를 조회한다 (Client Secret은 서버가 보냄)")
    void getUserId() {
        KakaoOAuthClient client = client(CLIENT_SECRET);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", CLIENT_ID);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("code", "auth-code");
        form.add("client_secret", CLIENT_SECRET);
        server.expect(requestTo(AUTH_BASE_URL + "/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_BASE_URL + "/v2/user/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer kakao-access-token"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getUserId("auth-code")).isEqualTo("4011223344");
        server.verify();
    }

    @Test
    @DisplayName("Client Secret을 쓰지 않는 앱이면 토큰 요청에 client_secret을 넣지 않는다")
    void getUserId_withoutClientSecret() {
        KakaoOAuthClient client = client("");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", CLIENT_ID);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("code", "auth-code");
        server.expect(requestTo(AUTH_BASE_URL + "/oauth/token"))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_BASE_URL + "/v2/user/me"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getUserId("auth-code")).isEqualTo("4011223344");
    }

    @Test
    @DisplayName("인가 코드가 만료·재사용돼 카카오가 invalid_grant로 거절하면 400 KAKAO_AUTH_FAILED (사용자 정보는 조회하지 않음)")
    void invalidGrant() {
        KakaoOAuthClient client = client(CLIENT_SECRET);
        server.expect(requestTo(AUTH_BASE_URL + "/oauth/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found\",\"error_code\":\"KOE320\"}"));

        assertThatThrownBy(() -> client.getUserId("used-code"))
                .isInstanceOf(DomainException.class)
                .isNotInstanceOf(KakaoApiException.class)
                .extracting("resultCode").isEqualTo("400-KAKAO_AUTH_FAILED");
        server.verify();
    }

    @Test
    @DisplayName("키 설정 오류(invalid_client)는 사용자가 다시 시도해도 해결되지 않으므로 502 KakaoApiException")
    void invalidClient() {
        KakaoOAuthClient client = client(CLIENT_SECRET);
        server.expect(requestTo(AUTH_BASE_URL + "/oauth/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"Bad client credentials\",\"error_code\":\"KOE010\"}"));

        assertThatThrownBy(() -> client.getUserId("auth-code"))
                .isInstanceOfSatisfying(KakaoApiException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(401);
                    assertThat(e.getCode()).isEqualTo("KOE010");
                    assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR");
                });
    }

    @Test
    @DisplayName("사용자 정보 조회가 실패하거나(5xx) 카카오가 응답하지 않으면 502 KakaoApiException")
    void userInfoFailureAndTimeout() {
        KakaoOAuthClient client = client(CLIENT_SECRET);
        server.expect(requestTo(AUTH_BASE_URL + "/oauth/token"))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_BASE_URL + "/v2/user/me"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_HTML).body("<html>error</html>"));

        assertThatThrownBy(() -> client.getUserId("auth-code"))
                .isInstanceOfSatisfying(KakaoApiException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(500));

        KakaoOAuthClient timeoutClient = client(CLIENT_SECRET);
        server.expect(requestTo(AUTH_BASE_URL + "/oauth/token"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> timeoutClient.getUserId("auth-code"))
                .isInstanceOfSatisfying(KakaoApiException.class, e -> {
                    assertThat(e.getHttpStatus()).isZero();
                    assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR");
                });
    }
}
