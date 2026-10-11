package com.backend.boundedContext.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

@WebMvcTest(ApiV1AdminMemberController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminMemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberFacade memberFacade;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }

    @Test
    @DisplayName("회원 목록: 관리자는 검색 조건과 페이지로 조회하고 PageResponse 형식으로 받는다")
    void getMembers_admin() throws Exception {
        AdminMemberDto item = new AdminMemberDto(3L, "buyer@palette.test", "구매자", MemberRole.USER, MemberStatus.ACTIVE,
                LocalDateTime.of(2026, 10, 11, 15, 0));
        given(memberFacade.getMembers(eq("buyer"), eq(MemberStatus.ACTIVE), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 1, 10, 11, 2));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("email", "buyer").param("status", "ACTIVE").param("page", "1").param("size", "10")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].memberId").value(3))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(11));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(memberFacade).getMembers(eq("buyer"), eq(MemberStatus.ACTIVE), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("회원 목록: 관리자가 아니면 403 FORBIDDEN")
    void getMembers_forbidden() throws Exception {
        for (MemberRole role : new MemberRole[]{MemberRole.USER, MemberRole.SELLER}) {
            mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("회원 목록: 토큰이 없으면 401 UNAUTHORIZED")
    void getMembers_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("회원 목록: 없는 상태 값이면 400 INVALID_REQUEST")
    void getMembers_invalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("status", "SLEEPING").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("회원 상세: 판매자 상태와 지갑 잔액을 함께 돌려준다")
    void getMemberDetail_admin() throws Exception {
        given(memberFacade.getMemberDetail(2L)).willReturn(new AdminMemberDetailDto(
                2L, "seller@palette.test", "판매자", MemberRole.SELLER, MemberStatus.ACTIVE,
                LocalDateTime.of(2026, 10, 11, 15, 0), SellerStatus.APPROVED, new AdminMemberDetailDto.Wallet(30_000L, 0L)));

        mockMvc.perform(get("/api/v1/admin/users/2").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(2))
                .andExpect(jsonPath("$.data.sellerStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.wallet.balance").value(30000))
                .andExpect(jsonPath("$.data.wallet.heldAmount").value(0));
    }

    @Test
    @DisplayName("회원 상세: 없는 회원이면 404 MEMBER_NOT_FOUND")
    void getMemberDetail_notFound() throws Exception {
        given(memberFacade.getMemberDetail(99L)).willThrow(new DomainException(MemberErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/users/99").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("회원 상세: 관리자가 아니면 403 FORBIDDEN")
    void getMemberDetail_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/2").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }
}
