package com.backend.boundedContext.payment.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;

/**
 * 같은 멱등 키로는 원장이 두 번 저장되지 않는지 확인한다(idempotency_key 유니크).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class WalletTransactionRepositoryTest {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID와 멱등 키
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final String idempotencyKey = "TEST-" + UUID.randomUUID();

    private Wallet wallet;
    private WalletTransaction saved;

    @AfterEach
    void cleanUp() {
        if (saved != null) walletTransactionRepository.delete(saved);
        if (wallet != null) walletRepository.delete(wallet);
    }

    @Test
    @DisplayName("원장을 저장하면 멱등 키로 조회할 수 있고, 같은 키로는 다시 저장할 수 없다")
    void idempotencyKey_unique() {
        wallet = walletRepository.save(Wallet.create(memberId));
        saved = walletTransactionRepository.saveAndFlush(WalletTransaction.record(wallet,
                WalletTransactionType.CHARGE, 10_000, 1L, idempotencyKey));

        assertThat(walletTransactionRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        assertThatThrownBy(() -> walletTransactionRepository.saveAndFlush(WalletTransaction.record(wallet,
                WalletTransactionType.CHARGE, 10_000, 1L, idempotencyKey)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
