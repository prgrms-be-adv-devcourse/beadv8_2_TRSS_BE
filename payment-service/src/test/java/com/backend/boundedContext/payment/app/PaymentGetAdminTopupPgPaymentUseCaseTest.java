package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.app.dto.AdminTopupPgPaymentDto;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

class PaymentGetAdminTopupPgPaymentUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 21, 0);

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final TopupGateways topupGateways = mock(TopupGateways.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentGetAdminTopupPgPaymentUseCase useCase = new PaymentGetAdminTopupPgPaymentUseCase(topupRepository, topupGateways);

    @BeforeEach
    void setUp() {
        given(topupGateways.get(PgProvider.TOSS)).willReturn(gateway);
    }

    @Test
    @DisplayName("PG에 결제가 있으면 우리 기록과 PG 상태·취소 합계를 함께 돌려준다")
    void found() {
        Topup topup = cancelingTopup();
        topup.markManualCheck(TopupManualCheckReason.CANCEL_AMOUNT_MISMATCH, NOW);
        given(topupRepository.findById(7L)).willReturn(Optional.of(topup));
        given(gateway.getPayment(any())).willReturn(new PgPaymentResult("pk_7", topup.getOrderId(), PgPaymentStatus.PARTIALLY_CANCELED, "PARTIAL_CANCELED", 30_000, 25_000, PgPaymentMethod.CARD, NOW));

        AdminTopupPgPaymentDto dto = useCase.getPgPayment(7L);

        assertThat(dto.status()).isEqualTo(TopupStatus.CANCELING);
        assertThat(dto.cancelingAmount()).isEqualTo(30_000);
        assertThat(dto.manualCheckReason()).isEqualTo(TopupManualCheckReason.CANCEL_AMOUNT_MISMATCH);
        assertThat(dto.pg().result()).isEqualTo("FOUND");
        assertThat(dto.pg().status()).isEqualTo("PARTIALLY_CANCELED");
        assertThat(dto.pg().canceledAmount()).isEqualTo(25_000L);
        assertThat(dto.pg().method()).isEqualTo("CARD");
    }

    @Test
    @DisplayName("PG에 결제가 없으면 NOT_FOUND, 조회 결과를 모르면 UNKNOWN과 원인을 돌려준다 (우리 기록은 그대로)")
    void notFoundOrUnknown() {
        given(topupRepository.findById(7L)).willReturn(Optional.of(cancelingTopup()));

        // 첫 조회는 결제 없음, 두 번째 조회는 결과 모름
        given(gateway.getPayment(any()))
                .willThrow(new PgPaymentNotFoundException(new RuntimeException()))
                .willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));

        assertThat(useCase.getPgPayment(7L).pg().result()).isEqualTo("NOT_FOUND");
        AdminTopupPgPaymentDto unknown = useCase.getPgPayment(7L);
        assertThat(unknown.pg().result()).isEqualTo("UNKNOWN");
        assertThat(unknown.pg().message()).isEqualTo("Read timed out");
        assertThat(unknown.status()).isEqualTo(TopupStatus.CANCELING);
    }

    @Test
    @DisplayName("승인 요청 전(결제키 없음)이면 PG를 부르지 않고 NOT_REQUESTED")
    void notRequested() {
        Topup ready = Topup.create(3L, Wallet.create(3L), 30_000, PgProvider.TOSS, NOW);
        given(topupRepository.findById(7L)).willReturn(Optional.of(ready));

        assertThat(useCase.getPgPayment(7L).pg().result()).isEqualTo("NOT_REQUESTED");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("충전 건이 없으면 404")
    void notFoundTopup() {
        given(topupRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getPgPayment(7L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }

    private static Topup cancelingTopup() {
        Topup topup = Topup.create(3L, Wallet.create(3L), 30_000, PgProvider.TOSS, NOW);
        ReflectionTestUtils.setField(topup, "id", 7L);
        topup.markProcessing("pk_7", NOW);
        topup.approve(NOW);
        topup.startCancel();
        return topup;
    }
}
