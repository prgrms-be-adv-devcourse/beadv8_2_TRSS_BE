package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import com.backend.boundedContext.payment.app.dto.WalletTransactionDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.WalletTransactionReferenceType;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.payment.dto.WalletSummaryDto;

@WebMvcTest(ApiV1WalletController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @Test
    @DisplayName("지갑 조회: 토큰의 회원 ID로 잔액과 홀딩 중 금액을 돌려준다")
    void getWallet_success() throws Exception {
        given(paymentFacade.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 5_000L, 25_000L));

        mockMvc.perform(get("/api/v1/wallet").header("Authorization", "Bearer " + token(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.balance").value(5000))
                .andExpect(jsonPath("$.data.heldAmount").value(25000));
    }

    @Test
    @DisplayName("지갑 조회: 지갑이 없으면 404 WALLET_NOT_FOUND")
    void getWallet_notFound() throws Exception {
        given(paymentFacade.getWallet(3L)).willThrow(new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        mockMvc.perform(get("/api/v1/wallet").header("Authorization", "Bearer " + token(3L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-WALLET_NOT_FOUND"));
    }

    @Test
    @DisplayName("지갑 조회: 로그인하지 않으면 401")
    void getWallet_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/wallet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("거래 내역: 토큰의 회원 ID와 유형·기간 조건으로 조회한다")
    void getTransactions_success() throws Exception {
        WalletTransactionDto item = new WalletTransactionDto(10L, WalletTransactionType.PAYMENT, -12_000L, 18_000L,
                WalletTransactionReferenceType.PAYMENT, 7L, LocalDateTime.of(2026, 10, 17, 12, 0));
        given(paymentFacade.getWalletTransactions(eq(3L), eq(WalletTransactionType.PAYMENT),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 17)), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/wallet/transactions")
                        .param("type", "PAYMENT")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-17")
                        .header("Authorization", "Bearer " + token(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.items[0].transactionId").value(10))
                .andExpect(jsonPath("$.data.items[0].amount").value(-12000))
                .andExpect(jsonPath("$.data.items[0].referenceType").value("PAYMENT"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("거래 내역: 시작일이 종료일보다 늦으면 400 PAYMENT_PERIOD_INVALID")
    void getTransactions_invalidPeriod() throws Exception {
        given(paymentFacade.getWalletTransactions(eq(3L), any(), any(), any(), any(Pageable.class)))
                .willThrow(new DomainException(PaymentErrorCode.PAYMENT_PERIOD_INVALID));

        mockMvc.perform(get("/api/v1/wallet/transactions")
                        .param("from", "2026-10-17")
                        .param("to", "2026-10-01")
                        .header("Authorization", "Bearer " + token(3L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-PAYMENT_PERIOD_INVALID"));
    }

    @Test
    @DisplayName("거래 내역: 로그인하지 않으면 401")
    void getTransactions_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/transactions"))
                .andExpect(status().isUnauthorized());
    }

    private String token(Long memberId) {
        return jwtTokenProvider.createAccessToken(memberId, MemberRole.USER);
    }
}
