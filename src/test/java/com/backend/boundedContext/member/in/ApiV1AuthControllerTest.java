package com.backend.boundedContext.member.in;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.backend.boundedContext.member.app.AuthTokens;
import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;

import jakarta.servlet.http.Cookie;

@WebMvcTest(ApiV1AuthController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, RefreshTokenCookie.class, MemberPolicy.class})
class ApiV1AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberFacade memberFacade;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("이메일 중복 확인: 사용 가능하면 200")
    void checkEmail_success() throws Exception {
        postJson("/api/v1/auth/email/check", """
                {"email": "new@palette.com"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(memberFacade).checkEmail("new@palette.com");
    }

    @Test
    @DisplayName("이메일 중복 확인: 이미 가입된 이메일이면 409 EMAIL_DUPLICATED")
    void checkEmail_duplicated() throws Exception {
        willThrow(new DomainException(MemberErrorCode.EMAIL_DUPLICATED)).given(memberFacade).checkEmail("used@palette.com");

        postJson("/api/v1/auth/email/check", """
                {"email": "used@palette.com"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400 VALIDATION_FAILED")
    void checkEmail_invalidFormat() throws Exception {
        postJson("/api/v1/auth/email/check", """
                {"email": "not-an-email"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(memberFacade, never()).checkEmail(anyString());
    }

    @Test
    @DisplayName("인증코드 발송: 60초 안에 다시 요청하면 429 TOO_MANY_REQUESTS")
    void sendEmailCode_tooManyRequests() throws Exception {
        willThrow(new DomainException(GlobalErrorCode.TOO_MANY_REQUESTS)).given(memberFacade).sendEmailCode("new@palette.com");

        postJson("/api/v1/auth/email/code", """
                {"email": "new@palette.com"}
                """)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.resultCode").value("429-TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("인증코드 확인: 성공하면 인증 완료 토큰을 돌려준다")
    void verifyEmailCode_success() throws Exception {
        given(memberFacade.verifyEmailCode("new@palette.com", "123456")).willReturn("verified-token");

        postJson("/api/v1/auth/email/verify", """
                {"email": "new@palette.com", "code": "123456"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationToken").value("verified-token"));
    }

    @Test
    @DisplayName("인증코드 확인: 숫자 6자리가 아니면 400 VALIDATION_FAILED")
    void verifyEmailCode_invalidCodeFormat() throws Exception {
        postJson("/api/v1/auth/email/verify", """
                {"email": "new@palette.com", "code": "12ab"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("회원가입: 성공하면 회원 ID를 돌려준다")
    void signUp_success() throws Exception {
        given(memberFacade.signUp("new@palette.com", "palette123!", "홍길동", "verified-token")).willReturn(1L);

        postJson("/api/v1/auth/signup", """
                {"email": "new@palette.com", "password": "palette123!", "name": "홍길동", "verificationToken": "verified-token"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(1));
    }

    @Test
    @DisplayName("회원가입: 비밀번호 규칙(8~20자, 영문·숫자·특수문자, 한글·공백 불가)에 맞지 않으면 400 VALIDATION_FAILED")
    void signUp_invalidPassword() throws Exception {
        for (String password : new String[]{"pal123!", "palette1234", "palette!!!!", "12345678!", "palette123!palette123!", "palette1가!", "palette 12!"}) {
            postJson("/api/v1/auth/signup", """
                    {"email": "new@palette.com", "password": "%s", "name": "홍길동", "verificationToken": "verified-token"}
                    """.formatted(password))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].field").value("password"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].rejectedValue").doesNotExist());
        }

        verify(memberFacade, never()).signUp(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("회원가입: 인증 완료 토큰이 유효하지 않으면 400 EMAIL_NOT_VERIFIED")
    void signUp_notVerified() throws Exception {
        given(memberFacade.signUp("new@palette.com", "palette123!", "홍길동", "expired-token"))
                .willThrow(new DomainException(MemberErrorCode.EMAIL_NOT_VERIFIED));

        postJson("/api/v1/auth/signup", """
                {"email": "new@palette.com", "password": "palette123!", "name": "홍길동", "verificationToken": "expired-token"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("로그인: 성공하면 Access Token은 바디, Refresh Token은 HttpOnly 쿠키로 내려준다")
    void login_success() throws Exception {
        given(memberFacade.login("user@palette.com", "palette123!")).willReturn(new AuthTokens("access-token", "refresh-token"));

        postJson("/api/v1/auth/login", """
                {"email": "user@palette.com", "password": "palette123!"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().value("refreshToken", "refresh-token"))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andExpect(cookie().path("refreshToken", "/api/v1/auth"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
    }

    @Test
    @DisplayName("로그인: 이메일·비밀번호가 맞지 않으면 401 LOGIN_FAILED")
    void login_failed() throws Exception {
        given(memberFacade.login("user@palette.com", "wrong123!"))
                .willThrow(new DomainException(MemberErrorCode.LOGIN_FAILED));

        postJson("/api/v1/auth/login", """
                {"email": "user@palette.com", "password": "wrong123!"}
                """)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-LOGIN_FAILED"));
    }

    @Test
    @DisplayName("재발급: 쿠키의 Refresh Token으로 새 토큰을 받고 쿠키도 새 값으로 바뀐다 (Access Token 없이 호출 가능)")
    void refresh_success() throws Exception {
        given(memberFacade.refresh("old-refresh")).willReturn(new AuthTokens("new-access", "new-refresh"));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refreshToken", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                .andExpect(cookie().value("refreshToken", "new-refresh"));
    }

    @Test
    @DisplayName("재발급: Refresh Token 쿠키가 없으면 401 AUTH_TOKEN_EXPIRED")
    void refresh_noCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-AUTH_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("로그아웃: Access Token이 있으면 Refresh Token을 폐기하고 쿠키를 지운다")
    void logout_success() throws Exception {
        String accessToken = jwtTokenProvider.createAccessToken(1L, MemberRole.USER);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refreshToken", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("refreshToken", 0));

        verify(memberFacade).logout("refresh-token");
    }

    @Test
    @DisplayName("로그아웃: Access Token이 없으면 401 UNAUTHORIZED")
    void logout_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));

        verify(memberFacade, never()).logout(anyString());
    }

    @Test
    @DisplayName("위조된 Access Token이면 401 UNAUTHORIZED")
    void forgedToken_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("USER 토큰으로 관리자 경로에 접근하면 403 FORBIDDEN")
    void adminPath_forbiddenForUser() throws Exception {
        String accessToken = jwtTokenProvider.createAccessToken(1L, MemberRole.USER);

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("CORS: 허용한 프론트 주소의 preflight 요청은 쿠키 전송(credentials)까지 허용한다")
    void corsPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
}
