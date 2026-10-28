package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.out.MemberApi;

class PaymentPrepareTopupUseCaseTest {

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final MemberApi memberApi = mock(MemberApi.class);
    private PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;

    @BeforeEach
    void setUp() {
        PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);
        paymentPrepareTopupUseCase = new PaymentPrepareTopupUseCase(paymentPolicy, walletRepository, topupRepository, memberApi);
    }

    @Test
    @DisplayName("유효한 금액이면 회원 지갑으로 READY 충전 건을 저장하고 주문번호를 돌려준다")
    void prepareTopup_success() {
        Wallet wallet = Wallet.create(1L);
        given(walletRepository.findByMemberId(1L)).willReturn(Optional.of(wallet));
        given(topupRepository.save(any(Topup.class))).willAnswer(invocation -> invocation.getArgument(0));

        TopupPrepareDto result = paymentPrepareTopupUseCase.prepareTopup(1L, 20_000);

        ArgumentCaptor<Topup> saved = ArgumentCaptor.forClass(Topup.class);
        verify(topupRepository).save(saved.capture());
        assertThat(saved.getValue().getMemberId()).isEqualTo(1L);
        assertThat(saved.getValue().getWallet()).isSameAs(wallet);
        assertThat(saved.getValue().getAmount()).isEqualTo(20_000);
        assertThat(saved.getValue().getStatus()).isEqualTo(TopupStatus.READY);
        assertThat(result.orderId()).isEqualTo(saved.getValue().getTossOrderId()).startsWith("TOPUP-");
        assertThat(result.amount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("금액이 정책에 맞지 않으면 지갑을 조회하지 않고 TOPUP_AMOUNT_INVALID")
    void prepareTopup_invalidAmount() {
        assertThatThrownBy(() -> paymentPrepareTopupUseCase.prepareTopup(1L, 15_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-TOPUP_AMOUNT_INVALID");

        verify(walletRepository, never()).findByMemberId(any());
        verify(topupRepository, never()).save(any());
    }

    @Test
    @DisplayName("탈퇴한 회원이면 401 UNAUTHORIZED이고 충전 건을 만들지 않는다 (탈퇴 후 남은 Access Token)")
    void prepareTopup_withdrawnMember() {
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberApi).checkActiveMember(1L);

        assertThatThrownBy(() -> paymentPrepareTopupUseCase.prepareTopup(1L, 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("401-UNAUTHORIZED");

        verify(topupRepository, never()).save(any());
    }

    @Test
    @DisplayName("지갑이 없으면 WALLET_NOT_FOUND")
    void prepareTopup_walletNotFound() {
        given(walletRepository.findByMemberId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentPrepareTopupUseCase.prepareTopup(1L, 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-WALLET_NOT_FOUND");

        verify(topupRepository, never()).save(any());
    }
}
