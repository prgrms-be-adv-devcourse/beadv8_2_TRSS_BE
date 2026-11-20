package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;

/**
 * QA 보강 (TRSS-209): Spring Boot가 구성한 JsonMapper로 결제 내부 API가 배송비·지원금을 받는지.
 * 두 필드는 필수다: 값 있음 → 그대로 전달, 빠짐·null → 400이고 결제를 부르지 않는다.
 */
@WebMvcTest(InternalPaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalPaymentControllerShippingFeeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @Test
    @DisplayName("배송비·지원금이 있으면 그 값이 PayCommand로 넘어간다")
    void pay_withShippingFeeAndCouponSubsidy() throws Exception {
        given(paymentFacade.pay(any(PayCommand.class)))
                .willReturn(new PayResultDto(9L, PayResultStatus.PAID, 0, 0));

        mockMvc.perform(post("/internal/v1/payments/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 500, "orderNumber": "ORD-500", "buyerId": 3, "amount": 50500,
                                 "shippingFee": 2500, "couponSubsidyAmount": 3000,
                                 "orderExpiresAt": "2026-11-02T10:15:00", "idempotencyKey": "ORDER-500-PAY-abc"}
                                """))
                .andExpect(status().isOk());

        verify(paymentFacade).pay(new PayCommand(500L, "ORD-500", 3L, 50_500, 2_500, 3_000,
                LocalDateTime.of(2026, 11, 2, 10, 15), "ORDER-500-PAY-abc"));
    }

    @Test
    @DisplayName("배송비·지원금이 빠진 옛 본문은 400이고 결제를 부르지 않는다")
    void pay_missingNewFields_isBadRequest() throws Exception {
        mockMvc.perform(post("/internal/v1/payments/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 500, "orderNumber": "ORD-500", "buyerId": 3, "amount": 48000,
                                 "orderExpiresAt": "2026-11-02T10:15:00", "idempotencyKey": "ORDER-500-PAY-abc"}
                                """))
                .andExpect(status().isBadRequest());

        verify(paymentFacade, never()).pay(any());
    }

    @Test
    @DisplayName("shippingFee가 null이면 400이고 결제를 부르지 않는다")
    void pay_nullShippingFee_isBadRequest() throws Exception {
        mockMvc.perform(post("/internal/v1/payments/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 500, "orderNumber": "ORD-500", "buyerId": 3, "amount": 48000,
                                 "shippingFee": null, "couponSubsidyAmount": 0,
                                 "orderExpiresAt": "2026-11-02T10:15:00", "idempotencyKey": "ORDER-500-PAY-abc"}
                                """))
                .andExpect(status().isBadRequest());

        verify(paymentFacade, never()).pay(any());
    }

    @Test
    @DisplayName("amount가 빠진 본문은 400이고 결제를 부르지 않는다")
    void pay_missingAmount_isBadRequest() throws Exception {
        mockMvc.perform(post("/internal/v1/payments/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 500, "orderNumber": "ORD-500", "buyerId": 3,
                                 "shippingFee": 2500, "couponSubsidyAmount": 0,
                                 "orderExpiresAt": "2026-11-02T10:15:00", "idempotencyKey": "ORDER-500-PAY-abc"}
                                """))
                .andExpect(status().isBadRequest());

        verify(paymentFacade, never()).pay(any());
    }
}
