package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;

class PaymentTopupCancelProcessorTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long TOPUP_ID = 7L;

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);

    @Test
    @DisplayName("취소 시작: 충전 건의 PG가 연동되지 않았으면 취소 중으로 바꾸지 않고 IllegalStateException")
    void start_pgNotSupported() {
        PaymentTopupCancelProcessor processor =
                new PaymentTopupCancelProcessor(topupRepository, walletRepository, walletTransactionRepository, new TopupGateways(List.of(), TestPgCircuitBreakers.create()));
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = Topup.create(MEMBER_ID, wallet, 20_000, PgProvider.TOSS, LocalDateTime.now());
        ReflectionTestUtils.setField(topup, "id", TOPUP_ID);
        topup.markProcessing("pk_1", LocalDateTime.now());
        topup.approve(LocalDateTime.of(2026, 10, 13, 10, 0));
        given(walletRepository.findByMemberIdForUpdate(MEMBER_ID)).willReturn(Optional.of(wallet));
        given(topupRepository.findByIdAndMemberIdForUpdate(TOPUP_ID, MEMBER_ID)).willReturn(Optional.of(topup));

        assertThatThrownBy(() -> processor.start(MEMBER_ID, TOPUP_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getCancelingAmount()).isZero();
        verify(walletTransactionRepository, never()).save(any());
    }
}
