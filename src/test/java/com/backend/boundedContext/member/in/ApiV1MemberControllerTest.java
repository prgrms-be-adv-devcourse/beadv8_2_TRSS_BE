package com.backend.boundedContext.member.in;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.app.dto.WithdrawalNotAllowedResponse;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.domain.WithdrawalBlockReason;
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
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class, RefreshTokenCookie.class, MemberPolicy.class})
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

    @Test
    @DisplayName("탈퇴 조건 조회: 남은 조건과 탈퇴 가능 여부를 돌려준다")
    void getWithdrawalCheck() throws Exception {
        given(memberFacade.getWithdrawalCheck(3L)).willReturn(new WithdrawalCheckDto(
                false, true, 0, 30_000, List.of(WithdrawalBlockReason.ACTIVE_ORDERS, WithdrawalBlockReason.BALANCE_REMAINING)));
        String token = jwtTokenProvider.createAccessToken(3L, MemberRole.USER);

        mockMvc.perform(get("/api/v1/users/me/withdrawal").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawable").value(false))
                .andExpect(jsonPath("$.data.hasActiveOrders").value(true))
                .andExpect(jsonPath("$.data.balance").value(30000))
                .andExpect(jsonPath("$.data.reasons[0]").value("ACTIVE_ORDERS"))
                .andExpect(jsonPath("$.data.reasons[1]").value("BALANCE_REMAINING"));
    }

    @Test
    @DisplayName("탈퇴 조건 조회: 토큰이 없으면 401 UNAUTHORIZED")
    void getWithdrawalCheck_noToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/withdrawal"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("회원 탈퇴: 성공하면 Refresh Token 쿠키를 지운다")
    void withdraw_success() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"password": "palette123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(cookie().maxAge("refreshToken", 0));

        verify(memberFacade).withdraw(3L, "palette123!");
    }

    @Test
    @DisplayName("회원 탈퇴: 비밀번호가 비어 있으면 400 VALIDATION_FAILED")
    void withdraw_blankPassword() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"password": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(memberFacade, never()).withdraw(anyLong(), anyString());
    }

    @Test
    @DisplayName("회원 탈퇴: 조건이 남아 있으면 409 WITHDRAWAL_NOT_ALLOWED와 data.reasons, 쿠키는 그대로")
    void withdraw_notAllowed() throws Exception {
        willThrow(new DomainException(MemberErrorCode.WITHDRAWAL_NOT_ALLOWED,
                new WithdrawalNotAllowedResponse(List.of(WithdrawalBlockReason.SELLER_ACTIVE))))
                .given(memberFacade).withdraw(3L, "palette123!");

        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"password": "palette123!"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-WITHDRAWAL_NOT_ALLOWED"))
                .andExpect(jsonPath("$.data.reasons[0]").value("SELLER_ACTIVE"))
                .andExpect(cookie().doesNotExist("refreshToken"));
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(3L, MemberRole.USER);
    }
}
