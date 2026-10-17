package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
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
        AdminTopupDto item = new AdminTopupDto(14L, ORDER_ID, 3L, "buyer@palette.test", 20_000, 20_000,
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
}
