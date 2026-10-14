package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.app.dto.TopupFailDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.exception.DomainException;

class PaymentFailTopupUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long TOPUP_ID = 7L;

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentFailTopupUseCase useCase = new PaymentFailTopupUseCase(topupRepository);

    @Test
    @DisplayName("사용자가 결제창을 취소(PAY_PROCESS_CANCELED)하면 FAILED_USER_CANCEL과 토스 메시지를 기록한다")
    void failTopup_userCancel() {
        Topup topup = readyTopup();

        TopupFailDto result = useCase.failTopup(MEMBER_ID, TOPUP_ID, "PAY_PROCESS_CANCELED", "사용자에 의해 결제가 취소되었습니다.");

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_USER_CANCEL);
        assertThat(topup.getFailReason()).isEqualTo("사용자에 의해 결제가 취소되었습니다.");
        assertThat(result.topupId()).isEqualTo(TOPUP_ID);
        assertThat(result.status()).isEqualTo(TopupStatus.FAILED_USER_CANCEL);
    }

    @Test
    @DisplayName("그 외 결제창 실패 코드는 FAILED_PG_DECLINED로 기록한다")
    void failTopup_declined() {
        Topup topup = readyTopup();

        useCase.failTopup(MEMBER_ID, TOPUP_ID, "REJECT_CARD_COMPANY", "카드사에서 결제를 거절했습니다.");

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_PG_DECLINED);
        assertThat(topup.getFailReason()).isEqualTo("카드사에서 결제를 거절했습니다.");
    }

    @Test
    @DisplayName("토스 메시지가 없으면 기본 사유를 기록한다")
    void failTopup_noMessage() {
        Topup topup = readyTopup();

        useCase.failTopup(MEMBER_ID, TOPUP_ID, "PAY_PROCESS_ABORTED", null);

        assertThat(topup.getFailReason()).isEqualTo("결제가 완료되지 않았습니다.");
    }

    @Test
    @DisplayName("충전 건이 없거나 남의 충전 건이면 404 TOPUP_NOT_FOUND")
    void failTopup_notFound() {
        given(topupRepository.findByIdAndMemberIdForUpdate(TOPUP_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.failTopup(MEMBER_ID, TOPUP_ID, "PAY_PROCESS_CANCELED", "취소"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }

    @Test
    @DisplayName("승인 중(PROCESSING)인 충전 건은 결제창 실패로 덮어쓰지 않고 409")
    void failTopup_processing() {
        Topup topup = readyTopup();
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> useCase.failTopup(MEMBER_ID, TOPUP_ID, "PAY_PROCESS_CANCELED", "취소"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("이미 완료(DONE)된 충전 건은 409")
    void failTopup_done() {
        Topup topup = readyTopup();
        topup.markProcessing("pk_1");
        topup.approve(LocalDateTime.of(2026, 10, 14, 10, 0));

        assertThatThrownBy(() -> useCase.failTopup(MEMBER_ID, TOPUP_ID, "PAY_PROCESS_CANCELED", "취소"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
    }

    // 잠금 조회로 돌려줄 READY 충전 건
    private Topup readyTopup() {
        Topup topup = Topup.create(MEMBER_ID, Wallet.create(MEMBER_ID), 20_000);
        ReflectionTestUtils.setField(topup, "id", TOPUP_ID);
        given(topupRepository.findByIdAndMemberIdForUpdate(TOPUP_ID, MEMBER_ID)).willReturn(Optional.of(topup));
        return topup;
    }
}
