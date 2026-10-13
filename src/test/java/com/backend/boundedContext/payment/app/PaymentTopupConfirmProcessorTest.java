package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.exception.DomainException;

class PaymentTopupConfirmProcessorTest {

    private static final Long MEMBER_ID = 3L;

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentTopupConfirmProcessor processor = new PaymentTopupConfirmProcessor(topupRepository);

    @Test
    @DisplayName("승인 시작: READY 충전 건을 잠금 조회해 PROCESSING으로 바꾸고 결제키를 저장한다")
    void start_success() {
        Topup topup = readyTopup(20_000);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 20_000);

        assertThat(result.isStarted()).isTrue();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
        assertThat(topup.getPaymentKey()).isEqualTo("pk_1");
    }

    @Test
    @DisplayName("승인 시작: 금액이 다르면 FAILED_AMOUNT_MISMATCH로 기록하고 예외 대신 결과로 돌려준다")
    void start_amountMismatch() {
        Topup topup = readyTopup(20_000);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 10_000);

        assertThat(result.outcome()).isEqualTo(TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_AMOUNT_MISMATCH);
        assertThat(topup.getFailReason()).contains("20000", "10000");
        assertThat(topup.getPaymentKey()).isNull();
    }

    @Test
    @DisplayName("승인 시작: 충전 건이 없거나 남의 충전 건이면 404 TOPUP_NOT_FOUND")
    void start_notFound() {
        given(topupRepository.findByTossOrderIdAndMemberIdForUpdate("TOPUP-none", MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.start(MEMBER_ID, "TOPUP-none", "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }

    @Test
    @DisplayName("승인 시작: READY가 아니면 409 TOPUP_ALREADY_PROCESSED이고 상태는 그대로다")
    void start_notReady() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("승인 시작: 상태가 READY가 아니면 금액이 달라도 실패로 바꾸지 않는다")
    void start_notReadyWithDifferentAmount() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 10_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("승인 시작: 다른 충전 건에 이미 쓰인 결제키면 409 TOPUP_ALREADY_PROCESSED")
    void start_paymentKeyAlreadyUsed() {
        Topup topup = readyTopup(20_000);
        given(topupRepository.existsByPaymentKey("pk_used")).willReturn(true);

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_used", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.READY);
    }

    // 잠금 조회로 돌려줄 READY 충전 건
    private Topup readyTopup(long amount) {
        Topup topup = Topup.create(MEMBER_ID, Wallet.create(MEMBER_ID), amount);
        given(topupRepository.findByTossOrderIdAndMemberIdForUpdate(topup.getTossOrderId(), MEMBER_ID))
                .willReturn(Optional.of(topup));
        return topup;
    }
}
