package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;

/**
 * PROCESSING 결과 확인(대사)을 실제 DB로 확인한다 (토스 호출만 mock).
 * 오래된 PROCESSING 건 반영, 승인 응답과 대사가 겹쳐도 1회 반영, 확인 대기 시간 전 건 제외를 본다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupReconcileIntegrationTest {

    private static final long AMOUNT = 20_000;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    @Autowired
    private MemberRepository memberRepository;

    // 충전은 로그인 상태인 회원만 할 수 있어 실제 회원을 만든다
    private Long memberId;

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.save(Member.signUp("topup-it-" + UUID.randomUUID() + "@palette.com", "encoded", "회원")).getId();
        wallet = walletRepository.save(Wallet.create(memberId));
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAll(transactionsOfWallet());
        topupRepository.deleteAll(topupRepository.findAll().stream()
                .filter(topup -> topup.getMemberId().equals(memberId)).toList());
        walletRepository.deleteById(wallet.getId());
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("확인 대기 시간이 지난 PROCESSING 건은 토스 승인을 확인해 DONE·잔액·원장 CHARGE로 반영된다")
    void reconcile_approved() {
        Processing topup = processing();
        moveUpdatedAtMinutesAgo(topup.topupId(), 6);
        given(tossPaymentsClient.getPaymentByOrderId(topup.orderId())).willReturn(tossDone(topup));

        TopupReconcileResult result = paymentFacade.reconcileProcessingTopups();

        assertThat(result.completed()).isGreaterThanOrEqualTo(1);
        assertThat(topupRepository.findById(topup.topupId()).orElseThrow().getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isEqualTo(AMOUNT);
        assertThat(transactionsOfWallet()).hasSize(1);
    }

    @Test
    @DisplayName("승인 응답 반영과 대사가 동시에 같은 건을 반영해도 잔액·원장은 한 번만 반영된다")
    void reconcile_concurrentWithConfirm() throws Exception {
        Processing topup = processing();
        moveUpdatedAtMinutesAgo(topup.topupId(), 6);
        TossPaymentResponse toss = tossDone(topup);
        given(tossPaymentsClient.getPaymentByOrderId(topup.orderId())).willReturn(toss);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> confirm = executor.submit(() -> {
            start.await();
            return paymentTopupConfirmProcessor.complete(topup.topupId(), toss);   // 승인 응답 쪽 Tx2
        });
        Future<?> reconcile = executor.submit(() -> {
            start.await();
            return paymentFacade.reconcileProcessingTopups();                       // 스케줄러 쪽 대사
        });
        start.countDown();
        confirm.get(10, TimeUnit.SECONDS);
        reconcile.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(topupRepository.findById(topup.topupId()).orElseThrow().getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isEqualTo(AMOUNT);
        assertThat(transactionsOfWallet()).hasSize(1);
    }

    @Test
    @DisplayName("방금 PROCESSING이 된 건은 승인 요청이 진행 중일 수 있으므로 대사 대상에서 빠진다")
    void reconcile_skipsRecentProcessing() {
        Processing topup = processing();

        paymentFacade.reconcileProcessingTopups();

        verify(tossPaymentsClient, never()).getPaymentByOrderId(topup.orderId());
        assertThat(topupRepository.findById(topup.topupId()).orElseThrow().getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    private record Processing(Long topupId, String orderId) {}

    // 충전 준비 후 승인 시작(Tx1)까지 진행해 PROCESSING 건을 만든다
    private Processing processing() {
        TopupPrepareDto prepared = paymentFacade.prepareTopup(memberId, AMOUNT);
        paymentTopupConfirmProcessor.start(memberId, prepared.orderId(), "pk_reconcile_" + prepared.topupId(), AMOUNT);
        return new Processing(prepared.topupId(), prepared.orderId());
    }

    // updated_at은 앱(KST) 기준 LocalDateTime으로 저장되므로 앱 시각으로 과거 시각을 넣음
    private void moveUpdatedAtMinutesAgo(Long topupId, int minutes) {
        jdbcTemplate.update("update topup set updated_at = ? where id = ?", LocalDateTime.now().minusMinutes(minutes), topupId);
    }

    private TossPaymentResponse tossDone(Processing topup) {
        return new TossPaymentResponse("pk_reconcile_" + topup.topupId(), topup.orderId(), "DONE", AMOUNT, "카드", null);
    }

    private List<WalletTransaction> transactionsOfWallet() {
        return walletTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getWallet().getId().equals(wallet.getId()))
                .toList();
    }
}
