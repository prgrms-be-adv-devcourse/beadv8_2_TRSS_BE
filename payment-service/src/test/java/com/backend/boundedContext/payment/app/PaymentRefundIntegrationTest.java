package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.app.dto.InsufficientBalanceDto;
import com.backend.boundedContext.payment.app.dto.RefundDto;
import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.support.TestWalletCharger;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ReturnReasonCode;
import com.backend.shared.order.event.OrderReturnedEvent;
import com.backend.shared.payment.dto.PayCommand;

/**
 * 반품 환불(refundReturnedOrder: ① 요청 기록 → ② 환불 → ③ 실패 기록)을 실제 DB로 확인한다.
 * 결제는 실제 결제(pay)로 만든다. 예시 금액은 설계 문서와 같다: 결제 29,500(배송비 2,500 포함), 장바구니 쿠폰 지원금 3,000, 홀딩 30,000
 * 플랫폼(관리자) 회원 ID는 시드 관리자와 겹치지 않게 테스트용 값으로 바꾼다. 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.payment.adminMemberId=" + PaymentRefundIntegrationTest.PLATFORM_MEMBER_ID)
class PaymentRefundIntegrationTest {

    static final long PLATFORM_MEMBER_ID = 2_100_000_002L;

    @Autowired
    private PaymentFacade paymentFacade;

    @MockitoSpyBean
    private PaymentRefundUseCase paymentRefundUseCase;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentRefundRepository paymentRefundRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    private final long buyerMemberId = randomId();
    private final long orderId = randomId();

    private Wallet buyerWallet;
    private Wallet platformWallet;
    private Long paymentId;

    @BeforeEach
    void setUp() {
        Wallet buyer = Wallet.create(buyerMemberId);
        TestWalletCharger.charge(buyer, 30_000);
        buyerWallet = walletRepository.save(buyer);
        Wallet platform = Wallet.create(PLATFORM_MEMBER_ID);
        TestWalletCharger.charge(platform, 10_000);
        platformWallet = walletRepository.save(platform);
    }

    @AfterEach
    void cleanUp() {
        paymentRefundRepository.findByOrderId(orderId).ifPresent(refund -> {
            for (String key : List.of(refund.idempotencyKey(), refund.couponSubsidyIdempotencyKey(),
                    refund.shippingFeeIdempotencyKey(), refund.pickupFeeIdempotencyKey(), refund.pickupFeePaymentIdempotencyKey())) {
                walletTransactionRepository.findByIdempotencyKey(key).ifPresent(walletTransactionRepository::delete);
            }
            paymentRefundRepository.delete(refund);
        });
        if (paymentId != null) {
            walletHoldingRepository.findAll().stream()
                    .filter(h -> h.getPayment().getId().equals(paymentId))
                    .forEach(walletHoldingRepository::delete);
            for (String suffix : List.of("", "-SHIPPING_FEE", "-COUPON_SUBSIDY")) {
                walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId + suffix)
                        .ifPresent(walletTransactionRepository::delete);
            }
            paymentRepository.deleteById(paymentId);
        }
        walletRepository.delete(buyerWallet);
        walletRepository.delete(platformWallet);
    }

    @Test
    @DisplayName("불량 반품: 구매자는 배송비까지 전액 받고, 결제 REFUNDED·홀딩 RELEASED·환불 COMPLETED와 원장 3줄이 남는다")
    void refund_defective() {
        pay();

        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
        assertThat(balance(PLATFORM_MEMBER_ID)).isEqualTo(10_000);
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment().getRefundedAt()).isNotNull();
        assertThat(holding().getStatus()).isEqualTo(WalletHoldingStatus.RELEASED);

        PaymentRefund refund = refund();
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refund.getPaymentId()).isEqualTo(paymentId);
        assertThat(refund.getAmount()).isEqualTo(29_500);
        assertThat(refund.getReturnPickupFee()).isZero();
        assertThat(refund.getReason()).isEqualTo(ReturnReasonCode.DEFECTIVE);
        assertThat(refundLedger(refund)).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(WalletTransactionType.REFUND, 29_500L),
                        tuple(WalletTransactionType.COUPON_SUBSIDY_CANCEL, 3_000L),
                        tuple(WalletTransactionType.SHIPPING_FEE_REFUND, -2_500L));
    }

    @Test
    @DisplayName("단순 변심 반품: 확인만 하고 회수 택배비 결제 대기(PICKUP_FEE_REQUIRED)로 둔다. 돈은 움직이지 않는다")
    void refund_changeOfMind() {
        pay();

        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));

        PaymentRefund refund = refund();
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.PICKUP_FEE_REQUIRED);
        assertThat(refund.getPaymentId()).isEqualTo(paymentId);
        assertThat(refundLedger(refund)).isEmpty();
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(holding().getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(balance(buyerMemberId)).isEqualTo(500);
        assertThat(balance(PLATFORM_MEMBER_ID)).isEqualTo(9_500);

        // 승인 이벤트를 다시 받아도(Kafka 재전달) 결제 대기 그대로
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));
        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.PICKUP_FEE_REQUIRED);
    }

    @Test
    @DisplayName("단순 변심인데 홀딩이 일부라도 정산됐으면 결제 대기로 두지 않고 FAILED_HOLDING_SETTLED (택배비를 받아도 환불할 수 없음)")
    void refund_changeOfMindHoldingSettled() {
        pay();
        transactionTemplate.executeWithoutResult(status ->
                walletHoldingRepository.findByPaymentIdForUpdate(paymentId).orElseThrow().settle(1_000, LocalDateTime.now()));

        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));

        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.FAILED_HOLDING_SETTLED);
    }

    @Test
    @DisplayName("같은 반품 이벤트를 또 받거나(Kafka 재전달) 이벤트 ID가 달라도, 환불 기록은 하나이고 돈은 한 번만 나간다")
    void refund_twice() {
        pay();
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
        assertThat(balance(PLATFORM_MEMBER_ID)).isEqualTo(10_000);
        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refundLedger(refund())).hasSize(3);
    }

    @Test
    @DisplayName("이벤트 금액이 결제 금액과 다르면 돈을 옮기지 않고 FAILED_AMOUNT_MISMATCH로 남긴다 (예외 없음, 재시도 안 함)")
    void refund_amountMismatch() {
        pay();

        paymentFacade.refundReturnedOrder(event(29_000, ReturnReasonCode.DEFECTIVE));

        PaymentRefund refund = refund();
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.FAILED_AMOUNT_MISMATCH);
        assertThat(refund.getPaymentId()).isEqualTo(paymentId);
        assertThat(refund.getFailReason()).contains("29000", "29500");
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(balance(buyerMemberId)).isEqualTo(500);
    }

    @Test
    @DisplayName("주문에 결제 완료 건이 없으면 FAILED_PAYMENT_NOT_PAID로 남긴다 (예외 없음)")
    void refund_paymentNotPaid() {
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        PaymentRefund refund = refund();
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.FAILED_PAYMENT_NOT_PAID);
        assertThat(refund.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("일부라도 정산된 홀딩이면 돈을 옮기지 않고 FAILED_HOLDING_SETTLED로 남긴다 (예외 없음, 재시도 안 함)")
    void refund_holdingSettled() {
        pay();
        transactionTemplate.executeWithoutResult(status ->
                walletHoldingRepository.findByPaymentIdForUpdate(paymentId).orElseThrow().settle(1_000, LocalDateTime.now()));

        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.FAILED_HOLDING_SETTLED);
        assertThat(refund().getPaymentId()).isEqualTo(paymentId);   // ②가 롤백돼도 ③이 다시 찾아 남긴다
        assertThat(refund().getRetryCount()).isZero();
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(holding().getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(balance(buyerMemberId)).isEqualTo(500);
    }

    @Test
    @DisplayName("일시 오류면 FAILED_SYSTEM_ERROR를 남기고 예외를 다시 던진다(Kafka 재시도). 다시 받으면 같은 기록으로 환불을 끝낸다")
    void refund_systemErrorThenRetry() {
        pay();
        doThrow(new IllegalStateException("DB 연결 끊김")).doCallRealMethod()
                .when(paymentRefundUseCase).refund(anyLong());

        assertThatThrownBy(() -> paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE)))
                .hasMessage("DB 연결 끊김");
        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.FAILED_SYSTEM_ERROR);
        assertThat(refund().getPaymentId()).isEqualTo(paymentId);
        assertThat(refund().getRetryCount()).isEqualTo(1);
        assertThat(balance(buyerMemberId)).isEqualTo(500);

        // Kafka가 같은 메시지를 다시 준 상황
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refund().getFailReason()).isNull();
        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("일시 오류가 5번 쌓이면 더 던지지 않고(메시지 처리 끝) FAILED_SYSTEM_ERROR로 남아 사람이 확인한다")
    void refund_systemErrorStopsAfterFive() {
        pay();
        doThrow(new IllegalStateException("DB 연결 끊김")).when(paymentRefundUseCase).refund(anyLong());

        for (int attempt = 1; attempt < PaymentRefund.MAX_SYSTEM_ERROR_RETRIES; attempt++) {
            assertThatThrownBy(() -> paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE)))
                    .isInstanceOf(IllegalStateException.class);
        }
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));   // 5번째: 기록만 하고 던지지 않음
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));   // 그 뒤로는 기록도 바꾸지 않음

        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.FAILED_SYSTEM_ERROR);
        assertThat(refund().getRetryCount()).isEqualTo(PaymentRefund.MAX_SYSTEM_ERROR_RETRIES);
        assertThat(balance(buyerMemberId)).isEqualTo(500);
    }

    // ── 단순 변심: 회수 택배비 결제 + 환불 ──

    @Test
    @DisplayName("단순 변심 결제 대기 조회: 받을 상품값 27,000원과 낼 회수 택배비 2,500원을 보여 준다")
    void getRefund_pickupFeeRequired() {
        pay();
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));

        RefundDto refund = paymentFacade.getRefund(buyerMemberId, orderId);

        assertThat(refund.status()).isEqualTo(PaymentRefundStatus.PICKUP_FEE_REQUIRED);
        assertThat(refund.refundAmount()).isEqualTo(27_000);
        assertThat(refund.pickupFee()).isEqualTo(2_500);
        assertThat(refund.refundedAt()).isNull();
    }

    @Test
    @DisplayName("회수 택배비 결제: 예치금이 2,500원보다 적으면 402와 충전 권장액, 아무것도 바뀌지 않는다. 충전하면 결제하고 상품값을 환불한다")
    void payPickupFee_insufficientThenPaid() {
        pay();   // 구매자 30,000 → 500
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));

        assertThatThrownBy(() -> paymentFacade.payReturnPickupFee(buyerMemberId, orderId))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException domainException = (DomainException) e;
                    assertThat(domainException.getResultCode()).isEqualTo("402-INSUFFICIENT_BALANCE");
                    assertThat(domainException.getData()).isEqualTo(new InsufficientBalanceDto(2_000, 10_000));
                });
        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.PICKUP_FEE_REQUIRED);
        assertThat(balance(buyerMemberId)).isEqualTo(500);

        chargeBuyer(10_000);   // 구매자 10,500
        RefundDto result = paymentFacade.payReturnPickupFee(buyerMemberId, orderId);

        assertThat(result.status()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(result.refundAmount()).isEqualTo(27_000);
        assertThat(result.pickupFee()).isEqualTo(2_500);
        assertThat(balance(buyerMemberId)).isEqualTo(35_000);        // 10,500 − 2,500 + 27,000
        assertThat(balance(PLATFORM_MEMBER_ID)).isEqualTo(15_000);   // 9,500 + 2,500 + 3,000
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(holding().getStatus()).isEqualTo(WalletHoldingStatus.RELEASED);
        assertThat(refund().getReturnPickupFee()).isEqualTo(2_500);
        assertThat(refundLedger(refund())).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(WalletTransactionType.RETURN_PICKUP_FEE_PAYMENT, -2_500L),
                        tuple(WalletTransactionType.RETURN_PICKUP_FEE, 2_500L),
                        tuple(WalletTransactionType.REFUND, 27_000L),
                        tuple(WalletTransactionType.COUPON_SUBSIDY_CANCEL, 3_000L));
    }

    @Test
    @DisplayName("회수 택배비 결제를 두 번 눌러도 돈은 한 번만 움직이고, 두 번째는 같은 결과를 돌려준다")
    void payPickupFee_twice() {
        pay();
        chargeBuyer(10_000);
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));
        paymentFacade.payReturnPickupFee(buyerMemberId, orderId);

        RefundDto second = paymentFacade.payReturnPickupFee(buyerMemberId, orderId);

        assertThat(second.status()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(second.refundAmount()).isEqualTo(27_000);
        assertThat(balance(buyerMemberId)).isEqualTo(35_000);
        assertThat(refundLedger(refund())).hasSize(4);
    }

    @Test
    @DisplayName("남의 주문이면 조회·결제 모두 404 REFUND_NOT_FOUND")
    void payPickupFee_otherBuyer() {
        pay();
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.CHANGE_OF_MIND));
        long otherBuyer = randomId();

        assertThatThrownBy(() -> paymentFacade.getRefund(otherBuyer, orderId))
                .isInstanceOf(DomainException.class).extracting("resultCode").isEqualTo("404-REFUND_NOT_FOUND");
        assertThatThrownBy(() -> paymentFacade.payReturnPickupFee(otherBuyer, orderId))
                .isInstanceOf(DomainException.class).extracting("resultCode").isEqualTo("404-REFUND_NOT_FOUND");
        assertThat(refund().getStatus()).isEqualTo(PaymentRefundStatus.PICKUP_FEE_REQUIRED);
    }

    @Test
    @DisplayName("단순 변심이 아닌 환불(불량, 이미 전액 환불됨)은 회수 택배비를 결제할 수 없다 (409)")
    void payPickupFee_notChangeOfMind() {
        pay();
        paymentFacade.refundReturnedOrder(event(29_500, ReturnReasonCode.DEFECTIVE));

        assertThatThrownBy(() -> paymentFacade.payReturnPickupFee(buyerMemberId, orderId))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-REFUND_PICKUP_FEE_NOT_REQUIRED");
        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
    }

    // 로컬 확인처럼 구매자 예치금을 채운다 (충전 원장은 남기지 않음)
    private void chargeBuyer(long amount) {
        transactionTemplate.executeWithoutResult(status ->
                TestWalletCharger.charge(walletRepository.findByMemberIdForUpdate(buyerMemberId).orElseThrow(), amount));
    }

    private void pay() {
        paymentId = paymentFacade.pay(new PayCommand(orderId, "ORD-TEST-" + orderId, buyerMemberId, 29_500, 2_500, 3_000,
                LocalDateTime.now().plusMinutes(15), "TEST-" + UUID.randomUUID())).paymentId();
    }

    private OrderReturnedEvent event(long refundAmount, ReturnReasonCode reason) {
        return new OrderReturnedEvent(orderId, buyerMemberId, refundAmount, LocalDateTime.now(), reason);
    }

    private PaymentRefund refund() {
        return paymentRefundRepository.findByOrderId(orderId).orElseThrow();
    }

    private Payment payment() {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private WalletHolding holding() {
        return walletHoldingRepository.findAll().stream()
                .filter(h -> h.getPayment().getId().equals(paymentId))
                .findFirst().orElseThrow();
    }

    private List<WalletTransaction> refundLedger(PaymentRefund refund) {
        return List.of(refund.idempotencyKey(), refund.couponSubsidyIdempotencyKey(), refund.shippingFeeIdempotencyKey(),
                        refund.pickupFeeIdempotencyKey(), refund.pickupFeePaymentIdempotencyKey()).stream()
                .flatMap(key -> walletTransactionRepository.findByIdempotencyKey(key).stream())
                .toList();
    }

    private long balance(long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
