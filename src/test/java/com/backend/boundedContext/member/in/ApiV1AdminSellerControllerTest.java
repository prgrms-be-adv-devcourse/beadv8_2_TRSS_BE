package com.backend.boundedContext.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminSellerCountsDto;
import com.backend.boundedContext.member.app.dto.AdminSellerDto;
import com.backend.boundedContext.member.app.dto.AdminSellerListDto;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerStatus;

@WebMvcTest(ApiV1AdminSellerController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminSellerControllerTest {

    private static final LocalDateTime APPLIED_AT = LocalDateTime.of(2026, 10, 13, 10, 0);
    private static final AdminSellerCountsDto COUNTS = new AdminSellerCountsDto(18, 3, 12, 2, 1, 0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberSellerFacade memberSellerFacade;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }

    @Test
    @DisplayName("판매자 목록: 관리자는 상태 필터와 페이지로 조회하고, 목록(PageResponse)과 탭별 건수를 함께 받는다")
    void getSellers_admin() throws Exception {
        AdminSellerDto item = new AdminSellerDto(5L, APPLIED_AT, "팔레트 뷰티", "1234567890", "seller@palette.test", SellerStatus.PENDING);
        given(memberSellerFacade.getSellersForAdmin(eq(SellerStatus.PENDING), any(Pageable.class)))
                .willReturn(new AdminSellerListDto(new PageResponse<>(List.of(item), 1, 10, 11, 2), COUNTS));

        mockMvc.perform(get("/api/v1/admin/sellers")
                        .param("status", "PENDING").param("page", "1").param("size", "10")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellers.items[0].sellerId").value(5))
                .andExpect(jsonPath("$.data.sellers.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.sellers.items[0].email").value("seller@palette.test"))
                .andExpect(jsonPath("$.data.sellers.page").value(1))
                .andExpect(jsonPath("$.data.sellers.totalElements").value(11))
                .andExpect(jsonPath("$.data.counts.all").value(18))
                .andExpect(jsonPath("$.data.counts.pending").value(3))
                .andExpect(jsonPath("$.data.counts.approved").value(12))
                .andExpect(jsonPath("$.data.counts.rejected").value(2))
                .andExpect(jsonPath("$.data.counts.withdrawRequested").value(1))
                .andExpect(jsonPath("$.data.counts.withdrawn").value(0))
                .andExpect(jsonPath("$.data.counts.sanctioned").value(0));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(memberSellerFacade).getSellersForAdmin(eq(SellerStatus.PENDING), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("판매자 목록: status를 비우면 전체(null)로 조회한다")
    void getSellers_all() throws Exception {
        given(memberSellerFacade.getSellersForAdmin(isNull(), any(Pageable.class)))
                .willReturn(new AdminSellerListDto(new PageResponse<>(List.of(), 0, 20, 0, 0), COUNTS));

        mockMvc.perform(get("/api/v1/admin/sellers").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.all").value(18));

        verify(memberSellerFacade).getSellersForAdmin(isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("판매자 목록·상세: 관리자가 아니면 403 FORBIDDEN")
    void forbidden() throws Exception {
        for (MemberRole role : new MemberRole[]{MemberRole.USER, MemberRole.SELLER}) {
            mockMvc.perform(get("/api/v1/admin/sellers").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
            mockMvc.perform(get("/api/v1/admin/sellers/5").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("판매자 목록: 없는 상태 값이면 400 INVALID_REQUEST")
    void getSellers_invalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sellers").param("status", "SLEEPING").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("판매자 상세: 판매자 정보와 이력을 돌려준다")
    void getSellerDetail_admin() throws Exception {
        given(memberSellerFacade.getSellerDetail(5L)).willReturn(new AdminSellerDetailDto(
                5L, 7L, "seller@palette.test", "판매자", SellerStatus.APPROVED,
                "팔레트 뷰티", "1234567890", "홍길동", "01012345678", "국민은행", "**********3456", "홍길동", APPLIED_AT,
                List.of(new AdminSellerDetailDto.History(null, SellerStatus.PENDING, null, APPLIED_AT, null, null, null, null),
                        new AdminSellerDetailDto.History(SellerStatus.PENDING, SellerStatus.APPROVED, null,
                                APPLIED_AT.plusDays(1), null, null, null, null))));

        mockMvc.perform(get("/api/v1/admin/sellers/5").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerId").value(5))
                .andExpect(jsonPath("$.data.accountNumber").value("**********3456"))
                .andExpect(jsonPath("$.data.histories[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.histories[1].toStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("판매자 상세: 없는 판매자면 404 SELLER_NOT_FOUND")
    void getSellerDetail_notFound() throws Exception {
        given(memberSellerFacade.getSellerDetail(99L)).willThrow(new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/sellers/99").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-SELLER_NOT_FOUND"));
    }

    @Test
    @DisplayName("입점 승인: 관리자는 승인하고 바뀐 판매자 상세를 받는다")
    void approve_admin() throws Exception {
        given(memberSellerFacade.approveSeller(5L)).willReturn(detail(SellerStatus.APPROVED));

        mockMvc.perform(post("/api/v1/admin/sellers/5/approve").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("입점 승인: 심사 대기가 아니면 409 SELLER_STATE_INVALID")
    void approve_stateInvalid() throws Exception {
        given(memberSellerFacade.approveSeller(5L)).willThrow(new DomainException(MemberErrorCode.SELLER_STATE_INVALID));

        mockMvc.perform(post("/api/v1/admin/sellers/5/approve").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-SELLER_STATE_INVALID"));
    }

    @Test
    @DisplayName("입점 반려: 관리자는 사유와 함께 반려한다")
    void reject_admin() throws Exception {
        given(memberSellerFacade.rejectSeller(5L, "사업자등록증 확인 불가")).willReturn(detail(SellerStatus.REJECTED));

        mockMvc.perform(post("/api/v1/admin/sellers/5/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "사업자등록증 확인 불가"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    @DisplayName("입점 반려: 사유가 비어 있으면 400 VALIDATION_FAILED")
    void reject_blankReason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sellers/5/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(memberSellerFacade, never()).rejectSeller(any(), any());
    }

    @Test
    @DisplayName("입점 승인·반려: 관리자가 아니면 403 FORBIDDEN")
    void review_forbidden() throws Exception {
        for (MemberRole role : new MemberRole[]{MemberRole.USER, MemberRole.SELLER}) {
            mockMvc.perform(post("/api/v1/admin/sellers/5/approve").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
            mockMvc.perform(post("/api/v1/admin/sellers/5/reject").header("Authorization", bearer(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason": "사유"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        }
        verify(memberSellerFacade, never()).approveSeller(any());
        verify(memberSellerFacade, never()).rejectSeller(any(), any());
    }

    @Test
    @DisplayName("철회 승인: 진행 중인 판매가 있으면 409 ACTIVE_SALES_EXIST")
    void approveWithdrawal_activeSales() throws Exception {
        given(memberSellerFacade.approveWithdrawal(5L)).willThrow(new DomainException(MemberErrorCode.ACTIVE_SALES_EXIST));

        mockMvc.perform(post("/api/v1/admin/sellers/5/withdrawal/approve").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-ACTIVE_SALES_EXIST"));
    }

    @Test
    @DisplayName("철회 승인·거절: 관리자는 처리하고 바뀐 판매자 상세를 받는다")
    void withdrawal_admin() throws Exception {
        given(memberSellerFacade.approveWithdrawal(5L)).willReturn(detail(SellerStatus.WITHDRAWN));
        given(memberSellerFacade.rejectWithdrawal(5L, "반품 처리 후 다시 요청")).willReturn(detail(SellerStatus.APPROVED));

        mockMvc.perform(post("/api/v1/admin/sellers/5/withdrawal/approve").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        mockMvc.perform(post("/api/v1/admin/sellers/5/withdrawal/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "반품 처리 후 다시 요청"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("철회 거절: 사유가 비어 있으면 400 VALIDATION_FAILED")
    void rejectWithdrawal_blankReason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sellers/5/withdrawal/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(memberSellerFacade, never()).rejectWithdrawal(any(), any());
    }

    @Test
    @DisplayName("철회 승인·거절: 관리자가 아니면 403 FORBIDDEN")
    void withdrawal_forbidden() throws Exception {
        for (MemberRole role : new MemberRole[]{MemberRole.USER, MemberRole.SELLER}) {
            mockMvc.perform(post("/api/v1/admin/sellers/5/withdrawal/approve").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
            mockMvc.perform(post("/api/v1/admin/sellers/5/withdrawal/reject").header("Authorization", bearer(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason": "사유"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        }
        verify(memberSellerFacade, never()).approveWithdrawal(any());
        verify(memberSellerFacade, never()).rejectWithdrawal(any(), any());
    }

    @Test
    @DisplayName("제재: 관리자는 사유와 함께 제재하고 바뀐 판매자 상세를 받는다")
    void sanction_admin() throws Exception {
        given(memberSellerFacade.sanctionSeller(5L, "위조 상품 판매")).willReturn(detail(SellerStatus.SANCTIONED));

        mockMvc.perform(post("/api/v1/admin/sellers/5/sanction").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "위조 상품 판매"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SANCTIONED"));
    }

    @Test
    @DisplayName("제재: 사유가 비어 있으면 400 VALIDATION_FAILED")
    void sanction_blankReason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sellers/5/sanction").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(memberSellerFacade, never()).sanctionSeller(any(), any());
    }

    @Test
    @DisplayName("제재: 관리자가 아니면 403 FORBIDDEN")
    void sanction_forbidden() throws Exception {
        for (MemberRole role : new MemberRole[]{MemberRole.USER, MemberRole.SELLER}) {
            mockMvc.perform(post("/api/v1/admin/sellers/5/sanction").header("Authorization", bearer(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason": "사유"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        }
        verify(memberSellerFacade, never()).sanctionSeller(any(), any());
    }

    private static AdminSellerDetailDto detail(SellerStatus status) {
        return new AdminSellerDetailDto(
                5L, 7L, "seller@palette.test", "판매자", status,
                "팔레트 뷰티", null, "홍길동", "01012345678", "국민은행", "**********3456", "홍길동", APPLIED_AT,
                List.of());
    }
}
