package com.backend.boundedContext.member.in;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

@WebMvcTest(ApiV1AuthController.class)
class ApiV1AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberFacade memberFacade;

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
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
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
}
