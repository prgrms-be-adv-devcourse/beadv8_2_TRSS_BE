package com.backend.global.security;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.in.ApiV1MemberController;
import com.backend.boundedContext.member.in.InternalMemberController;
import com.backend.boundedContext.member.in.RefreshTokenCookie;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

/**
 * 내부 API(/internal/**)는 내부 전용 키로만 부를 수 있다. 회원 내부 API로 확인한다
 * 키 값은 src/test/resources/application.properties의 custom.internal.apiKey
 */
@WebMvcTest({InternalMemberController.class, ApiV1MemberController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class InternalApiSecurityTest {

    private static final String INTERNAL_KEY = "test-internal-api-key";
    private static final String MEMBER_URL = "/internal/v1/members/1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberFacade memberFacade;

    @MockitoBean
    private RefreshTokenCookie refreshTokenCookie;

    @Test
    @DisplayName("내부 키가 맞으면 JWT 없이 내부 API를 부를 수 있다")
    void validKey_allowed() throws Exception {
        given(memberFacade.getMember(1L)).willReturn(member());

        mockMvc.perform(get(MEMBER_URL).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("내부 키가 없으면 401 INTERNAL_API_KEY_INVALID")
    void noKey_unauthorized() throws Exception {
        mockMvc.perform(get(MEMBER_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-INTERNAL_API_KEY_INVALID"));
    }

    @Test
    @DisplayName("내부 키가 틀리면 401 INTERNAL_API_KEY_INVALID")
    void wrongKey_unauthorized() throws Exception {
        mockMvc.perform(get(MEMBER_URL).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY + "-wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-INTERNAL_API_KEY_INVALID"));
    }

    @Test
    @DisplayName("사용자 JWT로는 내부 API를 부를 수 없다 (관리자 토큰도 401)")
    void userToken_unauthorized() throws Exception {
        mockMvc.perform(get(MEMBER_URL).header("Authorization", "Bearer " + token(MemberRole.ADMIN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-INTERNAL_API_KEY_INVALID"));
    }

    @Test
    @DisplayName("내부 키로는 사용자 API를 부를 수 없다 (기존 JWT 규칙 그대로 401 UNAUTHORIZED)")
    void internalKey_onUserApi_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("사용자 API는 기존대로 JWT로 부를 수 있다")
    void userToken_onUserApi_allowed() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isOk());
    }

    private String token(MemberRole role) {
        return jwtTokenProvider.createAccessToken(1L, role);
    }

    private static MemberDto member() {
        return new MemberDto(1L, "user@palette.com", "홍길동", MemberRole.USER, MemberStatus.ACTIVE);
    }
}
