package com.backend.boundedContext.payment.in;

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

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.global.exception.DomainException;
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

    private String token(Long memberId) {
        return jwtTokenProvider.createAccessToken(memberId, MemberRole.USER);
    }
}
