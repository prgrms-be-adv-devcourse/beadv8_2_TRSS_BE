package com.backend.boundedContext.member.in;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;

@WebMvcTest(ApiV1MemberController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberFacade memberFacade;

    @Test
    @DisplayName("내 정보 조회: 토큰의 회원 ID로 조회해 돌려준다")
    void getMyInfo_success() throws Exception {
        given(memberFacade.getMyInfo(3L)).willReturn(
                new MyInfoDto(3L, "buyer@palette.test", "구매자", MemberRole.USER, LocalDateTime.of(2026, 10, 11, 15, 0)));
        String token = jwtTokenProvider.createAccessToken(3L, MemberRole.USER);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.memberId").value(3))
                .andExpect(jsonPath("$.data.email").value("buyer@palette.test"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("내 정보 조회: 토큰이 없으면 401 UNAUTHORIZED")
    void getMyInfo_noToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("내 정보 조회: 탈퇴 등으로 회원이 유효하지 않으면 401 UNAUTHORIZED")
    void getMyInfo_inactiveMember() throws Exception {
        given(memberFacade.getMyInfo(3L)).willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED));
        String token = jwtTokenProvider.createAccessToken(3L, MemberRole.USER);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }
}
