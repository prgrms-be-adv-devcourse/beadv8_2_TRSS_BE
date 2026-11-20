package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.PgCircuitBreakers;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;

class PaymentPrepareTopupUseCaseTest {

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentMemberPort memberPort = mock(PaymentMemberPort.class);
    private final TopupGatewayPort toss = gateway(PgProvider.TOSS);
    private final TopupGatewayPort hecto = gateway(PgProvider.HECTO);
    private final PgCircuitBreakers circuitBreakers = TestPgCircuitBreakers.create();
    private PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;

    @BeforeEach
    void setUp() {
        PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 3, 60, 1L, 2_500);
        paymentPrepareTopupUseCase = new PaymentPrepareTopupUseCase(paymentPolicy, walletRepository, topupRepository, memberPort,
                new TopupGateways(List.of(toss, hecto), circuitBreakers));
        given(toss.checkout(any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("유효한 금액이면 기본 PG(토스)로 회원 지갑의 READY 충전 건을 결제 요청 시각과 함께 저장하고 주문번호를 돌려준다")
    void prepareTopup_success() {
        Wallet wallet = Wallet.create(1L);
        given(walletRepository.findByMemberId(1L)).willReturn(Optional.of(wallet));
        given(topupRepository.save(any(Topup.class))).willAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        TopupPrepareDto result = paymentPrepareTopupUseCase.prepareTopup(1L, 20_000);

        ArgumentCaptor<Topup> saved = ArgumentCaptor.forClass(Topup.class);
        verify(topupRepository).save(saved.capture());
        assertThat(saved.getValue().getMemberId()).isEqualTo(1L);
        assertThat(saved.getValue().getWallet()).isSameAs(wallet);
        assertThat(saved.getValue().getAmount()).isEqualTo(20_000);
        assertThat(saved.getValue().getStatus()).isEqualTo(TopupStatus.READY);
        assertThat(saved.getValue().getPgProvider()).isEqualTo(PgProvider.TOSS);
        assertThat(saved.getValue().getRequestedAt()).isBetween(before, LocalDateTime.now());
        assertThat(result.orderId()).isEqualTo(saved.getValue().getOrderId()).startsWith("TOPUP-");
        assertThat(result.amount()).isEqualTo(20_000);
        assertThat(result.pgProvider()).isEqualTo(PgProvider.TOSS);
        assertThat(result.checkout()).isNull();
    }

    @Test
    @DisplayName("토스가 사용 불가(서킷 OPEN)면 HECTO 충전 건을 저장하고, 충전 건 정보로 만든 결제창 주소·요청 값을 돌려준다")
    void prepareTopup_tossUnavailable_hecto() {
        open(PgProvider.TOSS);
        given(walletRepository.findByMemberId(1L)).willReturn(Optional.of(Wallet.create(1L)));
        given(topupRepository.save(any(Topup.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(hecto.checkout(any())).willAnswer(invocation -> {
            PgPaymentRef payment = invocation.getArgument(0);
            return Optional.of(new PgCheckout("https://window", Map.of("mchtTrdNo", payment.orderId())));
        });

        TopupPrepareDto result = paymentPrepareTopupUseCase.prepareTopup(1L, 20_000);

        ArgumentCaptor<Topup> saved = ArgumentCaptor.forClass(Topup.class);
        verify(topupRepository).save(saved.capture());
        assertThat(saved.getValue().getPgProvider()).isEqualTo(PgProvider.HECTO);
        verify(hecto).checkout(PgPaymentRef.from(saved.getValue()));
        assertThat(result.pgProvider()).isEqualTo(PgProvider.HECTO);
        assertThat(result.checkout().url()).isEqualTo("https://window");
        assertThat(result.checkout().params()).containsEntry("mchtTrdNo", result.orderId());
    }

    @Test
    @DisplayName("PG 키 설정 오류로 결제창 요청 값을 만들지 못하면 충전 건을 저장하지 않는다")
    void prepareTopup_checkoutNotConfigured() {
        open(PgProvider.TOSS);
        given(walletRepository.findByMemberId(1L)).willReturn(Optional.of(Wallet.create(1L)));
        given(hecto.checkout(any())).willThrow(new PgConfigurationException("HECTO_CONFIGURATION_ERROR", new IllegalStateException()));

        assertThatThrownBy(() -> paymentPrepareTopupUseCase.prepareTopup(1L, 20_000))
                .isInstanceOf(PgConfigurationException.class);

        verify(topupRepository, never()).save(any());
    }

    @Test
    @DisplayName("토스·헥토 모두 사용 불가면 503 TOPUP_PG_UNAVAILABLE이고 충전 건을 만들지 않는다")
    void prepareTopup_allUnavailable() {
        given(walletRepository.findByMemberId(1L)).willReturn(Optional.of(Wallet.create(1L)));
        open(PgProvider.TOSS);
        open(PgProvider.HECTO);

        assertThatThrownBy(() -> paymentPrepareTopupUseCase.prepareTopup(1L, 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("503-TOPUP_PG_UNAVAILABLE");

        verify(topupRepository, never()).save(any());
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
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberPort).checkActiveMember(1L);

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

    // 결과 모름을 최소 호출 수(5회)만큼 기록해 그 PG의 서킷을 OPEN으로 만듦
    private void open(PgProvider provider) {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> circuitBreakers.record(provider, () -> { throw new PgResultUnknownException(new RuntimeException("Connection reset")); }))
                    .isInstanceOf(PgResultUnknownException.class);
        }
    }

    private static TopupGatewayPort gateway(PgProvider provider) {
        TopupGatewayPort port = mock(TopupGatewayPort.class);
        given(port.provider()).willReturn(provider);
        return port;
    }
}
