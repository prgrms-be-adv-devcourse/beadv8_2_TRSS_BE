package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupManualResolveDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupPgPaymentDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupManualResolveAction;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.member.dto.MemberRole;

@WebMvcTest(ApiV1AdminTopupController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminTopupControllerTest {

    private static final String ORDER_ID = "TOPUP-88e21df8-bddb-4ff6-9ea1-dd5e1cc3cc04";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private PaymentFacade paymentFacade;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }

    @Test
    @DisplayName("충전 탭: 관리자는 검색어·기간·페이지로 조회한다")
    void getTopups_admin() throws Exception {
        AdminTopupDto item = new AdminTopupDto(14L, ORDER_ID, 3L, "buyer@palette.test", 20_000, 20_000, 0,
                TopupStatus.DONE, null, LocalDateTime.of(2026, 10, 13, 20, 47), LocalDateTime.of(2026, 10, 13, 20, 46));
        given(paymentFacade.getAdminTopups(eq("buyer@palette.test"), eq(LocalDate.of(2026, 10, 1)),
                eq(LocalDate.of(2026, 10, 17)), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/admin/topups")
                        .param("keyword", "buyer@palette.test")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-17")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.items[0].topupId").value(14))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.items[0].memberEmail").value("buyer@palette.test"))
                .andExpect(jsonPath("$.data.items[0].status").value("DONE"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("충전 탭: 시작일이 종료일보다 늦으면 400 PAYMENT_PERIOD_INVALID")
    void getTopups_invalidPeriod() throws Exception {
        given(paymentFacade.getAdminTopups(any(), any(), any(), any(Pageable.class)))
                .willThrow(new DomainException(PaymentErrorCode.PAYMENT_PERIOD_INVALID));

        mockMvc.perform(get("/api/v1/admin/topups")
                        .param("from", "2026-10-17")
                        .param("to", "2026-10-01")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-PAYMENT_PERIOD_INVALID"));
    }

    @Test
    @DisplayName("충전 탭: 관리자가 아니면 403")
    void getTopups_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/topups").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentFacade);
    }

    @Test
    @DisplayName("실패 거래(충전 확인 지연): 관리자는 기간·페이지로 공통 형식 목록을 조회한다")
    void getFailures_admin() throws Exception {
        AdminFailureItem item = new AdminFailureItem(AdminFailureType.TOPUP_DELAYED, LocalDateTime.of(2026, 11, 18, 10, 0),
                AdminFailureTargetType.MEMBER, 3L, "bu***@palette.test", 20_000L, "PROCESSING", "PG 승인 결과 확인 중",
                "AUTO_RECHECK", "자동 확인 대기", 14L, null);
        given(paymentFacade.getAdminTopupFailures(eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 18)), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/admin/topups/failures")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-18")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.items[0].type").value("TOPUP_DELAYED"))
                .andExpect(jsonPath("$.data.items[0].refId").value(14))
                .andExpect(jsonPath("$.data.items[0].handlingCode").value("AUTO_RECHECK"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("실패 거래(충전 확인 지연): 관리자가 아니면 403")
    void getFailures_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/topups/failures").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentFacade);
    }

    @Test
    @DisplayName("충전 PG 결제 조회: 관리자는 우리 기록과 PG 조회 결과를 함께 받는다")
    void getPgPayment_admin() throws Exception {
        AdminTopupPgPaymentDto dto = new AdminTopupPgPaymentDto(14L, ORDER_ID, PgProvider.TOSS, TopupStatus.CANCELING, 30_000, 0, 10_000, 20_000, 2,
                TopupManualCheckReason.CANCEL_AMOUNT_MISMATCH, LocalDateTime.of(2026, 11, 18, 21, 5),
                new AdminTopupPgPaymentDto.PgPayment("FOUND", "PARTIALLY_CANCELED", "PARTIAL_CANCELED", 30_000L, 25_000L, "CARD", null));
        given(paymentFacade.getAdminTopupPgPayment(14L)).willReturn(dto);

        mockMvc.perform(get("/api/v1/admin/topups/14/pg-payment").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.status").value("CANCELING"))
                .andExpect(jsonPath("$.data.canceledAmount").value(20_000))
                .andExpect(jsonPath("$.data.cancelingAmount").value(10_000))
                .andExpect(jsonPath("$.data.manualCheckReason").value("CANCEL_AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.data.pg.result").value("FOUND"))
                .andExpect(jsonPath("$.data.pg.canceledAmount").value(25_000));
    }

    @Test
    @DisplayName("수동 확인 정리: 관리자는 정리 방법을 보내고 정리한 뒤 상태를 받는다")
    void resolveManualCheck_admin() throws Exception {
        given(paymentFacade.resolveTopupManualCheck(1L, 14L, TopupManualResolveAction.RESTORE))
                .willReturn(new AdminTopupManualResolveDto(14L, TopupManualResolveAction.RESTORE, TopupStatus.DONE));

        mockMvc.perform(post("/api/v1/admin/topups/14/manual-resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESTORE\"}")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.action").value("RESTORE"))
                .andExpect(jsonPath("$.data.status").value("DONE"));
    }

    @Test
    @DisplayName("수동 확인 정리: 정리 방법이 없으면 400")
    void resolveManualCheck_noAction() throws Exception {
        mockMvc.perform(post("/api/v1/admin/topups/14/manual-resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentFacade);
    }

    @Test
    @DisplayName("수동 확인 정리: 수동 확인 건이 아니면 409")
    void resolveManualCheck_notManualCheck() throws Exception {
        given(paymentFacade.resolveTopupManualCheck(1L, 14L, TopupManualResolveAction.RESTORE))
                .willThrow(new DomainException(PaymentErrorCode.TOPUP_NOT_MANUAL_CHECK));

        mockMvc.perform(post("/api/v1/admin/topups/14/manual-resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESTORE\"}")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-TOPUP_NOT_MANUAL_CHECK"));
    }

    @Test
    @DisplayName("수동 확인 정리: 관리자가 아니면 403")
    void resolveManualCheck_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/topups/14/manual-resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESTORE\"}")
                        .header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentFacade);
    }

    @Test
    @DisplayName("충전 PG 결제 조회: 관리자가 아니면 403")
    void getPgPayment_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/topups/14/pg-payment").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentFacade);
    }
}
