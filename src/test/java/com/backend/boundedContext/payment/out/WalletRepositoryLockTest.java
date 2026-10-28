package com.backend.boundedContext.payment.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.domain.Wallet;

/**
 * 잠금 조회한 지갑에 동시에 입금해도 잔액이 빠짐없이 반영되는지 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class WalletRepositoryLockTest {

    private static final int THREADS = 10;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    @AfterEach
    void cleanUp() {
        walletRepository.findByMemberId(memberId).ifPresent(walletRepository::delete);
    }

    @Test
    @DisplayName("동시에 10번 입금해도 잠금 덕분에 잔액은 10번 모두 반영된다")
    void findByMemberIdForUpdate_concurrentDeposit() throws InterruptedException {
        walletRepository.save(Wallet.create(memberId));

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    transactionTemplate.executeWithoutResult(status ->
                            walletRepository.findByMemberIdForUpdate(memberId).orElseThrow().deposit(10_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isEqualTo(100_000);
    }
}
