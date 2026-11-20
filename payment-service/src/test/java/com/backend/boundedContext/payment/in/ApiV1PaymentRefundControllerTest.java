package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.InsufficientBalanceDto;
import com.backend.boundedContext.payment.app.dto.RefundDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.global.exception.DomainException;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;

@WebMvcTest(ApiV1PaymentRefundController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1PaymentRefundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @Test
    @DisplayName("환불 조회: 토큰의 회원 ID로 조회해 상태·받을 금액·낼 회수 택배비를 돌려준다")
    void getRefund() throws Exception {
        given(paymentFacade.getRefund(3L, 55L))
                .willReturn(new RefundDto(55L, PaymentRefundStatus.PICKUP_FEE_REQUIRED, 27_000, 2_500, null));

        mockMvc.perform(get("/api/v1/payments/refunds/55").header("Authorization", bearer(3L, MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.status").value("PICKUP_FEE_REQUIRED"))
                .andExpect(jsonPath("$.data.refundAmount").value(27000))
                .andExpect(jsonPath("$.data.pickupFee").value(2500));
    }

    @Test
    @DisplayName("회수 택배비 결제: 결제·환불 결과를 돌려준다")
    void payPickupFee() throws Exception {
        given(paymentFacade.payReturnPickupFee(3L, 55L)).willReturn(new RefundDto(55L, PaymentRefundStatus.COMPLETED,
                27_000, 2_500, LocalDateTime.of(2026, 11, 17, 15, 0)));

        mockMvc.perform(post("/api/v1/payments/refunds/55/pickup-fee").header("Authorization", bearer(3L, MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.refundAmount").value(27000));
    }

    @Test
    @DisplayName("회수 택배비 결제: 예치금이 모자라면 402와 모자란 금액·충전 권장액")
    void payPickupFee_insufficientBalance() throws Exception {
        given(paymentFacade.payReturnPickupFee(3L, 55L)).willThrow(
                new DomainException(PaymentErrorCode.INSUFFICIENT_BALANCE, new InsufficientBalanceDto(2_000, 10_000)));

        mockMvc.perform(post("/api/v1/payments/refunds/55/pickup-fee").header("Authorization", bearer(3L, MemberRole.USER)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.resultCode").value("402-INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.shortage").value(2000))
                .andExpect(jsonPath("$.data.chargeAmount").value(10000));
    }

    @Test
    @DisplayName("로그인하지 않으면 401, 관리자는 403 (구매자 기능)")
    void requiresBuyer() throws Exception {
        mockMvc.perform(get("/api/v1/payments/refunds/55"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/payments/refunds/55/pickup-fee").header("Authorization", bearer(1L, MemberRole.ADMIN)))
                .andExpect(status().isForbidden());
        verify(paymentFacade, never()).payReturnPickupFee(any(), any());
    }

    private String bearer(Long memberId, MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(memberId, role);
    }
}
