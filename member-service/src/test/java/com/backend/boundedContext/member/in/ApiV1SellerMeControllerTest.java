package com.backend.boundedContext.member.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerStatus;

@WebMvcTest(ApiV1SellerMeController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1SellerMeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberSellerFacade memberSellerFacade;

    @Test
    @DisplayName("판매자 정보 수정: 토큰의 회원 ID로 수정하고 결과를 돌려준다")
    void update_success() throws Exception {
        given(memberSellerFacade.updateSeller(eq(2L), any(SellerUpdateRequest.class))).willReturn(new MySellerResponse(
                10L, SellerStatus.APPROVED, "팔레트 뷰티 2호점", "1234567890", "홍길동", "01012345678",
                "국민은행", "**********3456", "홍길동", null,
                LocalDateTime.of(2026, 10, 12, 10, 0), LocalDateTime.of(2026, 10, 12, 11, 0), 0L));

        mockMvc.perform(patch("/api/v1/seller/me")
                        .header("Authorization", "Bearer " + token(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName": "팔레트 뷰티 2호점"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.businessName").value("팔레트 뷰티 2호점"))
                .andExpect(jsonPath("$.data.accountNumber").value("**********3456"))
                .andExpect(jsonPath("$.data.approvedAt").exists())
                .andExpect(jsonPath("$.data.unsettledAmount").value(0))
                .andExpect(jsonPath("$.data.statusChangedAt").doesNotExist());
    }

    @Test
    @DisplayName("판매자 정보 수정: 상호를 공백으로 보내면 400")
    void update_blankName() throws Exception {
        mockMvc.perform(patch("/api/v1/seller/me")
                        .header("Authorization", "Bearer " + token(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName": "  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("판매자 정보 수정: 관리자면 403 (판매자 URL은 회원만 통과)")
    void update_admin() throws Exception {
        mockMvc.perform(patch("/api/v1/seller/me")
                        .header("Authorization", "Bearer " + token(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName": "팔레트 뷰티 2호점"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("판매자 정보 수정: 토큰이 없으면 401 UNAUTHORIZED")
    void update_noToken() throws Exception {
        mockMvc.perform(patch("/api/v1/seller/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName": "팔레트 뷰티 2호점"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("철회 요청: 토큰의 회원 ID와 사유로 요청하고 결과를 돌려준다")
    void requestWithdrawal_success() throws Exception {
        given(memberSellerFacade.requestWithdrawal(2L, "폐업 예정")).willReturn(new MySellerResponse(
                10L, SellerStatus.WITHDRAW_REQUESTED, "팔레트 뷰티", "1234567890", "홍길동", "01012345678",
                "국민은행", "**********3456", "홍길동", "폐업 예정",
                LocalDateTime.of(2026, 10, 12, 10, 0), LocalDateTime.of(2026, 10, 12, 11, 0), 0L));

        mockMvc.perform(post("/api/v1/seller/me/withdrawal")
                        .header("Authorization", "Bearer " + token(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "폐업 예정"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.status").value("WITHDRAW_REQUESTED"))
                .andExpect(jsonPath("$.data.reason").value("폐업 예정"));
    }

    @Test
    @DisplayName("철회 요청: 사유가 없으면 400")
    void requestWithdrawal_blankReason() throws Exception {
        mockMvc.perform(post("/api/v1/seller/me/withdrawal")
                        .header("Authorization", "Bearer " + token(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": " "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("철회 요청: 관리자면 403 (판매자 URL은 회원만 통과)")
    void requestWithdrawal_admin() throws Exception {
        mockMvc.perform(post("/api/v1/seller/me/withdrawal")
                        .header("Authorization", "Bearer " + token(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "폐업 예정"}
                                """))
                .andExpect(status().isForbidden());
    }

    private String token(MemberRole role) {
        return jwtTokenProvider.createAccessToken(2L, role);
    }
}
