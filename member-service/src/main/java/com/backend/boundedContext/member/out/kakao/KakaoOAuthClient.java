package com.backend.boundedContext.member.out.kakao;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 로그인 API 클라이언트. 프론트가 받은 인가 코드로 카카오 회원번호와 프로필 이미지를 조회함
 * 1. 인가 코드 → 카카오 Access Token (kauth.kakao.com/oauth/token, Client Secret은 서버에만 있음)
 * 2. 카카오 Access Token → 회원번호·프로필 이미지 (kapi.kakao.com/v2/user/me)
 * 카카오 토큰은 사용자 정보를 받는 데만 쓰고 저장하지 않음. 우리 서비스 인증은 우리 JWT로만 함
 *
 * 실패
 * - 인가 코드가 만료·재사용됐거나 redirect_uri가 다름(invalid_grant) → 400 KAKAO_AUTH_FAILED (다시 로그인하면 됨)
 * - 키 설정 오류·카카오 5xx·응답 없음 → KakaoApiException (502)
 */
@Component
public class KakaoOAuthClient {

    private static final String INVALID_GRANT = "invalid_grant";
    private static final int PROFILE_IMAGE_URL_MAX_LENGTH = 500;   // member_social_account.profile_image_url 길이

    private final RestClient restClient;
    private final String authBaseUrl;
    private final String apiBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    // restClientBuilder: 스프링이 넣어 주는 builder(호출 기록 http_client_requests·추적 traceId가 붙음). RestClient.builder()로 직접 만들면 빠진다
    @Autowired
    public KakaoOAuthClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.kakao.authBaseUrl}") String authBaseUrl,
            @Value("${custom.kakao.apiBaseUrl}") String apiBaseUrl,
            @Value("${custom.kakao.clientId}") String clientId,
            @Value("${custom.kakao.clientSecret}") String clientSecret,
            @Value("${custom.kakao.redirectUri}") String redirectUri,
            @Value("${custom.kakao.connectTimeoutSeconds}") long connectTimeoutSeconds,
            @Value("${custom.kakao.readTimeoutSeconds}") long readTimeoutSeconds
    ) {
        this(restClientBuilder.requestFactory(requestFactory(connectTimeoutSeconds, readTimeoutSeconds)), authBaseUrl, apiBaseUrl, clientId, clientSecret, redirectUri);
    }

    // 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둠
    KakaoOAuthClient(RestClient.Builder builder, String authBaseUrl, String apiBaseUrl, String clientId, String clientSecret, String redirectUri) {
        this.restClient = builder.build();
        this.authBaseUrl = authBaseUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    // 프론트한테 받은 인가 코드로 카카오 회원번호와 프로필 이미지를 조회함
    public KakaoUser getUser(String authorizationCode) {
        String accessToken = requestAccessToken(authorizationCode);
        UserResponse user = requestUser(accessToken);
        return new KakaoUser(String.valueOf(user.id()), profileImageUrl(user));
    }

    /**
     * 프로필 이미지 주소를 꺼냄. 아래 경우는 보여 줄 이미지가 없으므로 null
     * - 프로필 동의 항목에 동의하지 않음 (kakao_account·profile이 없음)
     * - 카카오 기본 이미지 (is_default_image = true)
     * - 빈 값이거나 컬럼 길이를 넘는 값
     * http 주소는 https 사이트에서 혼합 콘텐츠로 막히므로 https로 바꿈 (카카오 이미지 서버는 https도 지원함)
     */
    private static String profileImageUrl(UserResponse user) {
        if (user.kakaoAccount() == null || user.kakaoAccount().profile() == null) return null;
        Profile profile = user.kakaoAccount().profile();
        if (Boolean.TRUE.equals(profile.isDefaultImage())) return null;

        String url = profile.profileImageUrl();
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("http://")) url = "https://" + url.substring("http://".length());
        return url.length() <= PROFILE_IMAGE_URL_MAX_LENGTH ? url : null;
    }

    // 프론트한테 받은 인가 코드로 토큰 요청을 하고, 카카오로부터 카카오 Access Token를 반환받음
    private String requestAccessToken(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("redirect_uri", redirectUri);
        form.add("code", authorizationCode);
        if (!clientSecret.isBlank()) form.add("client_secret", clientSecret);   // 콘솔에서 Client Secret을 켜면 필수

        try {
            return restClient.post()
                    .uri(authBaseUrl + "/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class)
                    .accessToken();
        } catch (RestClientResponseException e) {
            KakaoErrorResponse error = parseError(e);
            if (INVALID_GRANT.equals(error.error())) {
                throw new DomainException(MemberErrorCode.KAKAO_AUTH_FAILED, e);
            }
            throw new KakaoApiException(e.getStatusCode().value(), error.errorCode() != null ? error.errorCode() : error.error(), e);
        } catch (ResourceAccessException e) {
            throw new KakaoApiException(0, null, e);
        }
    }

    // 카카오 Access Token(Bearer)으로 사용자 정보를 조회해 회원번호·프로필을 받음
    private UserResponse requestUser(String accessToken) {
        return call(() -> restClient.get()
                .uri(apiBaseUrl + "/v2/user/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(UserResponse.class));
    }

    // 사용자 정보 조회는 방금 받은 토큰으로 바로 부르므로, 실패하면 모두 카카오 쪽 문제로 봄
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new KakaoApiException(e.getStatusCode().value(), null, e);
        } catch (ResourceAccessException e) {
            throw new KakaoApiException(0, null, e);
        }
    }

    // 오류 본문이 카카오 형식이 아니어도(장애 시 HTML 등) 원래 원인이 묻히지 않게 빈 값으로 돌려줌
    private static KakaoErrorResponse parseError(RestClientResponseException e) {
        try {
            KakaoErrorResponse error = e.getResponseBodyAs(KakaoErrorResponse.class);
            if (error != null) return error;
        } catch (RuntimeException ignored) {
            // 아래 빈 값 사용
        }
        return new KakaoErrorResponse(null, null);
    }

    // 실제 HTTP 연결 부품. 접속·응답 대기 제한 시간을 걸어 카카오가 느려도 요청이 무한정 붙잡히지 않게 함
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutSeconds, long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }

    // 토큰 응답 중 access_token만 사용함 (refresh_token 등은 저장하지 않음)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token")
            String accessToken
    ) {}

    // 회원번호와 프로필 이미지만 사용함 (이메일·닉네임 등 다른 항목은 받지 않음)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(
            long id,
            @JsonProperty("kakao_account")
            KakaoAccount kakaoAccount   // 동의 항목이 하나도 없으면 없을 수 있음
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAccount(
            Profile profile   // 프로필 동의 항목에 동의하지 않았으면 없음
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(
            @JsonProperty("profile_image_url")
            String profileImageUrl,
            @JsonProperty("is_default_image")
            Boolean isDefaultImage
    ) {}
}
