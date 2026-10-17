package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import com.backend.boundedContext.payment.app.dto.WalletTransactionDto;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;

/**
 * 내 예치금 거래 내역 조회를 실제 DB로 확인한다 (내 지갑만, 최신순, 페이징, 유형·기간 필터).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class WalletTransactionHistoryIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final Long otherMemberId = memberId + 1;

    private final List<Wallet> wallets = new ArrayList<>();
    private final List<WalletTransaction> transactions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAll(transactions);
        walletRepository.deleteAll(wallets);
    }

    @Test
    @DisplayName("내 지갑의 거래만 최신순으로 페이지 단위로 조회되고, 차감은 음수 금액으로 나온다")
    void getTransactions() {
        Wallet wallet = saveWallet(memberId);
        Wallet otherWallet = saveWallet(otherMemberId);
        Long charge1 = record(wallet, WalletTransactionType.CHARGE, 30_000);
        Long payment = record(wallet, WalletTransactionType.PAYMENT, 12_000);
        Long charge2 = record(wallet, WalletTransactionType.CHARGE, 10_000);
        record(otherWallet, WalletTransactionType.CHARGE, 50_000);

        PageResponse<WalletTransactionDto> page0 = paymentFacade.getWalletTransactions(memberId, null, null, null, PageRequest.of(0, 2));
        PageResponse<WalletTransactionDto> page1 = paymentFacade.getWalletTransactions(memberId, null, null, null, PageRequest.of(1, 2));

        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.items()).extracting(WalletTransactionDto::transactionId).containsExactly(charge2, payment);
        assertThat(page1.items()).extracting(WalletTransactionDto::transactionId).containsExactly(charge1);
        assertThat(page0.items().get(1).amount()).isEqualTo(-12_000);
        assertThat(page0.items().get(1).balanceAfter()).isEqualTo(18_000);
    }

    @Test
    @DisplayName("유형을 주면 그 유형의 거래만 조회된다")
    void filterByType() {
        Wallet wallet = saveWallet(memberId);
        Long charge = record(wallet, WalletTransactionType.CHARGE, 30_000);
        record(wallet, WalletTransactionType.PAYMENT, 12_000);

        PageResponse<WalletTransactionDto> result = paymentFacade.getWalletTransactions(
                memberId, WalletTransactionType.CHARGE, null, null, PageRequest.of(0, 20));

        assertThat(result.items()).extracting(WalletTransactionDto::transactionId).containsExactly(charge);
    }

    @Test
    @DisplayName("기간은 양 끝 날짜를 포함하고, 기간 밖의 거래는 빠진다")
    void filterByPeriod() {
        Wallet wallet = saveWallet(memberId);
        Long charge = record(wallet, WalletTransactionType.CHARGE, 30_000);
        LocalDate today = LocalDate.now();

        PageResponse<WalletTransactionDto> todayOnly = paymentFacade.getWalletTransactions(
                memberId, null, today, today, PageRequest.of(0, 20));
        PageResponse<WalletTransactionDto> untilYesterday = paymentFacade.getWalletTransactions(
                memberId, null, null, today.minusDays(1), PageRequest.of(0, 20));

        assertThat(todayOnly.items()).extracting(WalletTransactionDto::transactionId).containsExactly(charge);
        assertThat(untilYesterday.items()).isEmpty();
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 400 PAYMENT_PERIOD_INVALID, 지갑이 없으면 404 WALLET_NOT_FOUND")
    void invalidRequests() {
        LocalDate today = LocalDate.now();

        assertThatThrownBy(() -> paymentFacade.getWalletTransactions(memberId, null, today, today.minusDays(1), PageRequest.of(0, 20)))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-PAYMENT_PERIOD_INVALID");
        assertThatThrownBy(() -> paymentFacade.getWalletTransactions(memberId, null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-WALLET_NOT_FOUND");
    }

    private Wallet saveWallet(Long ownerId) {
        Wallet wallet = walletRepository.save(Wallet.create(ownerId));
        wallets.add(wallet);
        return wallet;
    }

    // 원장 기록 후 지갑 잔액도 함께 저장하고 원장 ID를 돌려줌
    private Long record(Wallet wallet, WalletTransactionType type, long amount) {
        WalletTransaction transaction = walletTransactionRepository.save(
                WalletTransaction.record(wallet, type, amount, 1L, "TEST-" + UUID.randomUUID()));
        walletRepository.save(wallet);
        transactions.add(transaction);
        return transaction.getId();
    }
}
