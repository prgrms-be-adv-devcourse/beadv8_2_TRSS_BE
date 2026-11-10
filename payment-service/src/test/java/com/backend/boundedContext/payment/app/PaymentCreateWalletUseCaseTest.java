package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.WalletRepository;

@ExtendWith(MockitoExtension.class)
class PaymentCreateWalletUseCaseTest {

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private PaymentCreateWalletUseCase paymentCreateWalletUseCase;

    @Test
    @DisplayName("지갑이 없으면 잔액 0인 지갑을 만든다")
    void createWallet_new() {
        given(walletRepository.existsByMemberId(1L)).willReturn(false);

        paymentCreateWalletUseCase.createWallet(1L);

        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(saved.capture());
        assertThat(saved.getValue().getMemberId()).isEqualTo(1L);
        assertThat(saved.getValue().getBalance()).isZero();
    }

    @Test
    @DisplayName("이미 지갑이 있으면 아무것도 하지 않는다")
    void createWallet_alreadyExists() {
        given(walletRepository.existsByMemberId(1L)).willReturn(true);

        paymentCreateWalletUseCase.createWallet(1L);

        verify(walletRepository, never()).save(any());
    }
}
