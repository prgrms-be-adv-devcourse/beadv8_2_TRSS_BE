package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.global.exception.DomainException;

/**
 * 충전 준비 → 승인 흐름을 실제 DB로 확인한다 (토스 호출만 mock).
 * 잠금으로 같은 승인 요청이 한 번만 반영되는지, 금액 불일치 실패 기록이 커밋되는지 본다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupConfirmIntegrationTest {

    private static final long AMOUNT = 20_000;
    private static final String PAYMENT_KEY = "pk_test_integration";
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 10, 13, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final String paymentKey = PAYMENT_KEY + "-" + memberId;

    private Wallet wallet;
    private String orderId;

    @BeforeEach
    void setUp() {
        wallet = walletRepository.save(Wallet.create(memberId));
        TopupPrepareDto prepared = paymentFacade.prepareTopup(memberId, AMOUNT);
        orderId = prepared.orderId();
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAll(transactionsOfWallet());
        topupRepository.deleteAll(topupRepository.findAll().stream()
                .filter(topup -> topup.getMemberId().equals(memberId)).toList());
        walletRepository.deleteById(wallet.getId());
    }

    @Test
    @DisplayName("승인 성공: 충전 건 DONE, 지갑 잔액, 원장 CHARGE가 함께 저장된다")
    void confirm_success() {
        given(tossPaymentsClient.confirm(paymentKey, orderId, AMOUNT)).willReturn(tossDone());

        TopupConfirmDto result = paymentFacade.confirmTopup(memberId, paymentKey, orderId, AMOUNT);

        assertThat(result.status()).isEqualTo(TopupStatus.DONE);
        assertThat(result.balance()).isEqualTo(AMOUNT);

        Topup topup = savedTopup();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getPaymentKey()).isEqualTo(paymentKey);
        assertThat(topup.getRemainingAmount()).isEqualTo(AMOUNT);
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isEqualTo(AMOUNT);

        List<WalletTransaction> transactions = transactionsOfWallet();
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getType()).isEqualTo(WalletTransactionType.CHARGE);
        assertThat(transactions.get(0).getAmount()).isEqualTo(AMOUNT);
        assertThat(transactions.get(0).getBalanceAfter()).isEqualTo(AMOUNT);
        assertThat(transactions.get(0).getIdempotencyKey()).isEqualTo(topup.chargeIdempotencyKey());
    }

    @Test
    @DisplayName("동시 승인: 같은 요청이 동시에 두 번 와도 토스는 한 번만 호출되고 잔액도 한 번만 늘어난다")
    void confirm_concurrent() throws Exception {
        // 첫 요청이 토스 호출 중인 동안 두 번째 요청이 들어오도록 응답을 늦춘다
        given(tossPaymentsClient.confirm(paymentKey, orderId, AMOUNT)).willAnswer(invocation -> {
            Thread.sleep(300);
            return tossDone();
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> confirm = () -> {
            start.await();
            try {
                return paymentFacade.confirmTopup(memberId, paymentKey, orderId, AMOUNT);
            } catch (DomainException e) {
                return e.getResultCode();
            }
        };
        List<Future<Object>> futures = new ArrayList<>();
        futures.add(executor.submit(confirm));
        futures.add(executor.submit(confirm));
        start.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(results).filteredOn(TopupConfirmDto.class::isInstance).hasSize(1);
        assertThat(results).filteredOn("409-TOPUP_ALREADY_PROCESSED"::equals).hasSize(1);
        verify(tossPaymentsClient, times(1)).confirm(anyString(), anyString(), anyLong());
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isEqualTo(AMOUNT);
        assertThat(transactionsOfWallet()).hasSize(1);
    }

    @Test
    @DisplayName("금액 불일치: 토스를 호출하지 않고 400, FAILED_AMOUNT_MISMATCH는 롤백되지 않고 남는다")
    void confirm_amountMismatch() {
        assertThatThrownBy(() -> paymentFacade.confirmTopup(memberId, paymentKey, orderId, 10_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-AMOUNT_MISMATCH");

        verify(tossPaymentsClient, never()).confirm(anyString(), eq(orderId), anyLong());
        Topup topup = savedTopup();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_AMOUNT_MISMATCH);
        assertThat(topup.getFailReason()).contains("20000", "10000");
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isZero();
        assertThat(transactionsOfWallet()).isEmpty();
    }

    private TossPaymentResponse tossDone() {
        return new TossPaymentResponse(paymentKey, orderId, "DONE", AMOUNT, "카드", APPROVED_AT);
    }

    private Topup savedTopup() {
        return topupRepository.findByTossOrderIdAndMemberId(orderId, memberId).orElseThrow();
    }

    private List<WalletTransaction> transactionsOfWallet() {
        return walletTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getWallet().getId().equals(wallet.getId()))
                .toList();
    }
}
