package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 결제 내부 API의 요청·응답 모양을 확인한다.
 * /internal 접근 제한(보안 설정)은 이 테스트 범위가 아니라서 보안 필터는 끈다.
 */
@WebMvcTest(InternalPaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @Test
    @DisplayName("지갑 조회: PaymentApi.getWallet 결과를 RsData로 돌려준다")
    void getWallet() throws Exception {
        given(paymentFacade.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 30_000, 5_000));

        mockMvc.perform(get("/internal/v1/payments/wallets/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.memberId").value(3))
                .andExpect(jsonPath("$.data.balance").value(30_000))
                .andExpect(jsonPath("$.data.heldAmount").value(5_000));
    }

    @Test
    @DisplayName("결과가 정해지지 않은 충전 건 조회: true/false를 data로 돌려준다")
    void hasPendingTopups() throws Exception {
        given(paymentFacade.hasPendingTopups(3L)).willReturn(true);

        mockMvc.perform(get("/internal/v1/payments/wallets/3/pending-topups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("결제: 요청 본문을 PayCommand로 받아 결과를 돌려준다. 잔액 부족도 실패 결과로 200")
    void pay() throws Exception {
        given(paymentFacade.pay(any(PayCommand.class)))
                .willReturn(new PayResultDto(9L, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 18_000, 20_000));

        mockMvc.perform(post("/internal/v1/payments/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 500, "orderNumber": "ORD-500", "buyerId": 3, "amount": 48000,
                                 "orderExpiresAt": "2026-11-02T10:15:00", "idempotencyKey": "ORDER-500-PAY-abc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(9))
                .andExpect(jsonPath("$.data.status").value("FAILED_INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.shortage").value(18_000))
                .andExpect(jsonPath("$.data.chargeAmount").value(20_000));

        verify(paymentFacade).pay(new PayCommand(500L, "ORD-500", 3L, 48_000,
                LocalDateTime.of(2026, 11, 2, 10, 15), "ORDER-500-PAY-abc"));
    }

    @Test
    @DisplayName("결제 실패 예외는 같은 resultCode와 상태 코드로 돌려준다 (409-PAYMENT_ALREADY_PAID)")
    void pay_domainException() throws Exception {
        willThrow(new DomainException(PaymentErrorCode.PAYMENT_ALREADY_PAID)).given(paymentFacade).pay(any());

        mockMvc.perform(post("/internal/v1/payments/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 500, "orderNumber": "ORD-500", "buyerId": 3, "amount": 48000,
                                 "orderExpiresAt": "2026-11-02T10:15:00", "idempotencyKey": "ORDER-500-PAY-abc"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-PAYMENT_ALREADY_PAID"));
    }

    @Test
    @DisplayName("결제 취소: 경로의 멱등 키로 cancelPayment를 부르고 200")
    void cancelPayment() throws Exception {
        mockMvc.perform(post("/internal/v1/payments/ORDER-500-PAY-abc/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(paymentFacade).cancelPayment("ORDER-500-PAY-abc");
    }

    @Test
    @DisplayName("결제 시도 목록: 주문의 결제 시도를 목록으로 돌려준다")
    void getPaymentAttempts() throws Exception {
        given(paymentFacade.getPaymentAttempts(500L)).willReturn(List.of(
                new PaymentAttemptDto(9L, PaymentAttemptStatus.CANCELED, 48_000,
                        LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 2, 10, 0), null)));

        mockMvc.perform(get("/internal/v1/payments/orders/500/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].paymentId").value(9))
                .andExpect(jsonPath("$.data[0].status").value("CANCELED"));
    }

    @Test
    @DisplayName("정산 지급: 요청 본문을 SettlementTransferCommand로 받아 결과를 돌려준다")
    void transfer() throws Exception {
        given(paymentFacade.transfer(any())).willReturn(new TransferResultDto("SETTLEMENT-31", 77L, 90_000, 10_000,
                LocalDateTime.of(2026, 11, 10, 3, 0)));

        mockMvc.perform(post("/internal/v1/payments/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey": "SETTLEMENT-31", "settlementId": 31, "sellerMemberId": 2,
                                 "payoutAmount": 90000, "feeAmount": 10000,
                                 "orders": [{"orderId": 500, "amount": 100000}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walletTransactionId").value(77))
                .andExpect(jsonPath("$.data.payoutAmount").value(90_000));
    }

    @Test
    @DisplayName("정산 지급 내역 조회: 없으면 data가 null")
    void findTransfer_empty() throws Exception {
        given(paymentFacade.findTransfer("SETTLEMENT-31")).willReturn(Optional.empty());

        mockMvc.perform(get("/internal/v1/payments/transfers/SETTLEMENT-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
