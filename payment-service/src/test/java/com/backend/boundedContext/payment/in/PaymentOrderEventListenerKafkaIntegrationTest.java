package com.backend.boundedContext.payment.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
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
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.PaymentRefundUseCase;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.support.TestWalletCharger;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.global.outbox.OutboxRelay;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ReturnReasonCode;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderReturnedEvent;
import com.backend.shared.payment.dto.PayCommand;

/**
 * 반품 승인 이벤트가 outbox → 릴레이 → Kafka(order.events) → 결제 컨슈머를 거쳐 환불되는지 확인한다.
 * Kafka는 EmbeddedKafka, DB는 로컬 Docker PostgreSQL. 릴레이는 직접 호출한다.
 * PaymentEventListenerKafkaIntegrationTest와 같은 주의: 같은 DB를 쓰는 앱(local 프로필)이 떠 있으면 그 앱의 릴레이가 이벤트를 먼저 가져간다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",   // 릴레이는 직접 호출한다
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "custom.payment.adminMemberId=" + PaymentOrderEventListenerKafkaIntegrationTest.PLATFORM_MEMBER_ID
})
@EmbeddedKafka(topics = "order.events", partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class PaymentOrderEventListenerKafkaIntegrationTest {

    static final long PLATFORM_MEMBER_ID = 2_100_000_003L;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired private EventPublisher eventPublisher;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PaymentFacade paymentFacade;
    @Autowired private WalletRepository walletRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private WalletHoldingRepository walletHoldingRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;

    /** 컨슈머가 환불을 몇 번 불렀는지 확인하고, 첫 호출만 실패시키는 데 쓴다. 기본은 실제 메서드를 부른다 */
    @MockitoSpyBean
    private PaymentRefundUseCase paymentRefundUseCase;

    private final long buyerMemberId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
    private final long orderId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);

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
        paymentId = paymentFacade.pay(new PayCommand(orderId, "ORD-TEST-" + orderId, buyerMemberId, 29_500, 2_500, 3_000,
                LocalDateTime.now().plusMinutes(15), "TEST-" + UUID.randomUUID())).paymentId();
    }

    @AfterEach
    void cleanUp() {
        paymentRefundRepository.findByOrderId(orderId).ifPresent(refund -> {
            for (String key : List.of(refund.idempotencyKey(), refund.couponSubsidyIdempotencyKey(),
                    refund.shippingFeeIdempotencyKey(), refund.pickupFeeIdempotencyKey())) {
                walletTransactionRepository.findByIdempotencyKey(key).ifPresent(walletTransactionRepository::delete);
            }
            paymentRefundRepository.delete(refund);
        });
        walletHoldingRepository.findAll().stream()
                .filter(h -> h.getPayment().getId().equals(paymentId))
                .forEach(walletHoldingRepository::delete);
        for (String suffix : List.of("", "-SHIPPING_FEE", "-COUPON_SUBSIDY")) {
            walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId + suffix)
                    .ifPresent(walletTransactionRepository::delete);
        }
        paymentRepository.deleteById(paymentId);
        walletRepository.delete(buyerWallet);
        walletRepository.delete(platformWallet);
        outboxEventRepository.deleteAll(findOutbox());
    }

    @Test
    @DisplayName("반품 승인 이벤트가 Kafka로 오면 환불한다: 결제 REFUNDED, 환불 기록 COMPLETED, 구매자 전액")
    void returnedEventIsRefunded() {
        UUID eventId = publish(returned(ReturnReasonCode.DEFECTIVE));

        relayUntilPublished(eventId);

        await().atMost(WAIT).until(() -> refundStatus() == PaymentRefundStatus.COMPLETED);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("order.events의 다른 주문 이벤트(구매 확정)는 환불하지 않고 건너뛴다")
    void otherOrderEventIsIgnored() {
        UUID confirmedId = publish(new OrderConfirmedEvent(orderId, buyerMemberId, LocalDateTime.now(), ConfirmType.MANUAL, List.of()));
        UUID returnedId = publish(returned(ReturnReasonCode.DEFECTIVE));   // 같은 주문(같은 파티션)이라 구매 확정 뒤에 처리된다

        relayUntilPublished(confirmedId);
        relayUntilPublished(returnedId);

        // 반품 승인이 처리됐으면 앞의 구매 확정도 이미 받은 것이다. 환불은 반품 승인 한 번만 불렸다
        await().atMost(WAIT).until(() -> refundStatus() == PaymentRefundStatus.COMPLETED);
        verify(paymentRefundUseCase, times(1)).refund(anyLong());
    }

    @Test
    @DisplayName("일시 오류면 예외를 던져 Kafka가 같은 메시지를 다시 주고, 두 번째에 환불을 끝낸다 (돈은 한 번만)")
    void transientFailureIsRetriedByKafka() {
        willThrow(new IllegalStateException("DB 연결 끊김")).willCallRealMethod()
                .given(paymentRefundUseCase).refund(anyLong());
        UUID eventId = publish(returned(ReturnReasonCode.DEFECTIVE));

        relayUntilPublished(eventId);

        await().atMost(WAIT).until(() -> refundStatus() == PaymentRefundStatus.COMPLETED);
        verify(paymentRefundUseCase, times(2)).refund(anyLong());
        PaymentRefund refund = paymentRefundRepository.findByOrderId(orderId).orElseThrow();
        assertThat(refund.getRetryCount()).isEqualTo(1);           // 첫 실패가 기록돼 있다
        assertThat(balance(buyerMemberId)).isEqualTo(30_000);      // 불량 전액 29,500원을 한 번만 받았다
    }

    private OrderReturnedEvent returned(ReturnReasonCode reason) {
        return new OrderReturnedEvent(orderId, buyerMemberId, 29_500, LocalDateTime.now(), reason);
    }

    private UUID publish(DomainEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(event));
        return findOutbox().getLast().getEventId();
    }

    // 로컬 DB에 남은 다른 PENDING 이벤트가 많으면 한 번에 닿지 않을 수 있어 여러 번 돌린다
    private void relayUntilPublished(UUID eventId) {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            if (outboxEventRepository.findByEventId(eventId).orElseThrow().getStatus() == OutboxEventStatus.PUBLISHED) return;
        }
        throw new AssertionError("릴레이를 100번 돌려도 PUBLISHED가 되지 않습니다: eventId=" + eventId);
    }

    private PaymentRefundStatus refundStatus() {
        return paymentRefundRepository.findByOrderId(orderId).map(PaymentRefund::getStatus).orElse(null);
    }

    private long balance(long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    // 이 테스트 주문의 outbox 이벤트 (저장 순서대로)
    private List<OutboxEvent> findOutbox() {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("ORDER") && outbox.getAggregateId().equals(orderId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }
}
