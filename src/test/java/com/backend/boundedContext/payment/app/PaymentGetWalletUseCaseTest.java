package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.WalletSummaryDto;

@ExtendWith(MockitoExtension.class)
class PaymentGetWalletUseCaseTest {

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private PaymentGetWalletUseCase paymentGetWalletUseCase;

    @Test
    @DisplayName("지갑 잔액을 돌려준다 (홀딩 구현 전이라 홀딩 금액은 0)")
    void getWallet() {
        given(walletRepository.findByMemberId(3L)).willReturn(Optional.of(Wallet.create(3L)));

        assertThat(paymentGetWalletUseCase.getWallet(3L)).isEqualTo(new WalletSummaryDto(3L, 0L, 0L));
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
