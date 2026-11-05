package com.backend.monolith.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.dto.SettledOrderAmountDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 결제 내부 API를 부르는 요청 모양과, 응답·오류를 PaymentApi 결과로 바꾸는지 확인한다.
 * 실제 결제 서비스 대신 MockRestServiceServer가 정해 둔 응답을 돌려준다.
 */
class PaymentApiHttpAdapterTest {

    private static final String BASE_URL = "http://payment-service:8083";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private static final PayCommand PAY_COMMAND = new PayCommand(500L, "ORD-500", 3L, 48_000,
            LocalDateTime.of(2026, 11, 2, 10, 15), "ORDER-500-PAY-abc");

    private MockRestServiceServer server;
    private PaymentApiHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new PaymentApiHttpAdapter(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("지갑 조회: GET /internal/v1/payments/wallets/{memberId}를 부르고 data를 꺼낸다")
    void getWallet() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(InternalApiKeyFilter.HEADER, INTERNAL_API_KEY))   // 내부 키 헤더를 함께 보낸다
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "지갑을 조회했습니다.",
                         "data": {"memberId": 3, "balance": 30000, "heldAmount": 5000}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.getWallet(3L)).isEqualTo(new WalletSummaryDto(3L, 30_000, 5_000));
    }

    @Test
    @DisplayName("결과가 정해지지 않은 충전 건 조회: data의 true/false를 돌려준다")
    void hasPendingTopups() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3/pending-topups"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "결과가 정해지지 않은 충전 건이 있는지 조회했습니다.", "data": true}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.hasPendingTopups(3L)).isTrue();
    }

    @Test
    @DisplayName("결제: PayCommand를 JSON 본문으로 보내고 결과를 꺼낸다. 잔액 부족도 실패 결과로 받는다")
    void pay() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/pay"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId").value(500))
                .andExpect(jsonPath("$.amount").value(48_000))
                .andExpect(jsonPath("$.orderExpiresAt").value("2026-11-02T10:15:00"))
                .andExpect(jsonPath("$.idempotencyKey").value("ORDER-500-PAY-abc"))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "결제를 처리했습니다.",
                         "data": {"paymentId": 9, "status": "FAILED_INSUFFICIENT_BALANCE", "shortage": 18000, "chargeAmount": 20000}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.pay(PAY_COMMAND))
                .isEqualTo(new PayResultDto(9L, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 18_000, 20_000));
    }

    @Test
    @DisplayName("결제 취소: 경로에 멱등 키를 넣어 POST로 부른다")
    void cancelPayment() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/ORDER-500-PAY-abc/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "결제 취소를 처리했습니다.", "data": null}
                        """, MediaType.APPLICATION_JSON));

        adapter.cancelPayment("ORDER-500-PAY-abc");

        server.verify();
    }

    @Test
    @DisplayName("결제 시도 목록: 주문의 결제 시도를 목록으로 돌려준다")
    void getPaymentAttempts() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/orders/500/attempts"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "결제 시도 목록을 조회했습니다.",
                         "data": [{"paymentId": 9, "status": "CANCELED", "amount": 48000,
                                   "attemptedAt": "2026-11-02T10:00:00", "paidAt": "2026-11-02T10:00:00", "refundedAt": null}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.getPaymentAttempts(500L)).containsExactly(
                new PaymentAttemptDto(9L, PaymentAttemptStatus.CANCELED, 48_000,
                        LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 2, 10, 0), null));
    }

    @Test
    @DisplayName("정산 지급: SettlementTransferCommand를 JSON 본문으로 보내고 결과를 꺼낸다")
    void transfer() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.idempotencyKey").value("SETTLEMENT-31"))
                .andExpect(jsonPath("$.orders[0].orderId").value(500))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "정산 지급을 처리했습니다.",
                         "data": {"idempotencyKey": "SETTLEMENT-31", "walletTransactionId": 77,
                                  "payoutAmount": 90000, "feeAmount": 10000, "transferredAt": "2026-11-10T03:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        TransferResultDto result = adapter.transfer(new SettlementTransferCommand("SETTLEMENT-31", 31L, 2L,
                90_000, 10_000, List.of(new SettledOrderAmountDto(500L, 100_000))));

        assertThat(result).isEqualTo(new TransferResultDto("SETTLEMENT-31", 77L, 90_000, 10_000,
                LocalDateTime.of(2026, 11, 10, 3, 0)));
    }

    @Test
    @DisplayName("정산 지급 내역 조회: data가 null이면 Optional.empty")
    void findTransfer_empty() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/transfers/SETTLEMENT-31"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCode": "200-1", "msg": "정산 지급 내역을 조회했습니다.", "data": null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.findTransfer("SETTLEMENT-31")).isEmpty();
    }

    @Test
    @DisplayName("결제 서비스가 RsData 오류로 응답하면 같은 resultCode의 DomainException (메서드를 직접 부를 때와 같게)")
    void rsDataError() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/pay"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"resultCode": "409-PAYMENT_ALREADY_PAID", "msg": "이미 결제된 주문입니다.", "data": null}
                                """));

        assertThatThrownBy(() -> adapter.pay(PAY_COMMAND))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("409-PAYMENT_ALREADY_PAID");
                    assertThat(e.getMsg()).isEqualTo("이미 결제된 주문입니다.");
                    assertThat(e.statusCode()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("응답이 RsData가 아니면(예: 게이트웨이 HTML 오류) 502 EXTERNAL_SERVICE_ERROR")
    void notRsDataError() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/wallets/3"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>502 Bad Gateway</html>"));

        assertThatThrownBy(() -> adapter.getWallet(3L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    @Test
    @DisplayName("결제 서비스에 닿지 못하면(시간 초과) 502 EXTERNAL_SERVICE_ERROR")
    void timeout() {
        server.expect(requestTo(BASE_URL + "/internal/v1/payments/pay"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> adapter.pay(PAY_COMMAND))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }
}
