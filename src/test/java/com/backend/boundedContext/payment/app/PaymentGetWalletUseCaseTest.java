package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.WalletSummaryDto;

@ExtendWith(MockitoExtension.class)
class PaymentGetWalletUseCaseTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletHoldingRepository walletHoldingRepository;

    @InjectMocks
    private PaymentGetWalletUseCase paymentGetWalletUseCase;

    @Test
    @DisplayName("지갑 잔액과 HELD 홀딩의 남은 금액 합계를 돌려준다")
    void getWallet() {
        Wallet wallet = Wallet.create(3L);
        wallet.deposit(5_000);
        given(walletRepository.findByMemberId(3L)).willReturn(Optional.of(wallet));
        given(walletHoldingRepository.sumRemainingAmount(any(), any())).willReturn(25_000L);

        assertThat(paymentGetWalletUseCase.getWallet(3L)).isEqualTo(new WalletSummaryDto(3L, 5_000L, 25_000L));
    }

    @Test
    @DisplayName("홀딩 중 금액은 HELD 상태만 더한다")
    void getWallet_heldOnly() {
        given(walletRepository.findByMemberId(3L)).willReturn(Optional.of(Wallet.create(3L)));
        given(walletHoldingRepository.sumRemainingAmount(any(), any())).willReturn(0L);

        paymentGetWalletUseCase.getWallet(3L);

        then(walletHoldingRepository).should()
                .sumRemainingAmount(any(), eq(WalletHoldingStatus.HELD));
    }

    @Test
    @DisplayName("지갑이 없으면 404 WALLET_NOT_FOUND")
    void getWallet_notFound() {
        given(walletRepository.findByMemberId(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentGetWalletUseCase.getWallet(3L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(PaymentErrorCode.WALLET_NOT_FOUND.resultCode());
    }
}
