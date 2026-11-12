package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupCancelDto;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

class PaymentCancelTopupUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long TOPUP_ID = 7L;
    private static final String PAYMENT_KEY = "pk_1";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final long CANCEL_AMOUNT = 15_000;
    private static final String CANCEL_KEY = "TOPUP-7-CANCEL-0";

    private final PaymentTopupCancelProcessor processor = mock(PaymentTopupCancelProcessor.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentMemberPort memberPort = mock(PaymentMemberPort.class);
    private final PaymentCancelTopupUseCase useCase;

    PaymentCancelTopupUseCaseTest() {
        given(gateway.provider()).willReturn(PgProvider.TOSS);
        useCase = new PaymentCancelTopupUseCase(processor, new TopupGateways(List.of(gateway)), memberPort);
    }

    @Test
    @DisplayName("취소 성공: 시작 → 충전 건의 PG로 남은 금액·멱등 키를 넘겨 취소 → 확정 결과를 돌려준다")
    void cancel_success() {
        started();
        TopupCancelDto done = new TopupCancelDto(TOPUP_ID, ORDER_ID, TopupStatus.DONE, CANCEL_AMOUNT, CANCEL_AMOUNT, 0L);
        given(processor.complete(TOPUP_ID, CANCEL_AMOUNT)).willReturn(done);

        TopupCancelDto result = useCase.cancelTopup(MEMBER_ID, TOPUP_ID);

        assertThat(result).isEqualTo(done);
        verify(gateway).cancel(PAYMENT_KEY, CANCEL_AMOUNT, "예치금 충전 취소", CANCEL_KEY);
        verify(processor, never()).fail(anyLong());
    }

    @Test
    @DisplayName("탈퇴한 회원: 취소를 시작하지 않고 PG도 호출하지 않으며 401 UNAUTHORIZED")
    void cancel_withdrawnMember() {
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberPort).checkActiveMember(MEMBER_ID);

        assertThatThrownBy(() -> useCase.cancelTopup(MEMBER_ID, TOPUP_ID))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("401-UNAUTHORIZED");
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("PG 결과 모름: 되돌리지 않고 CANCELING으로 응답한다 (스케줄러가 확정)")
    void cancel_unknown() {
        started();
        given(gateway.cancel(PAYMENT_KEY, CANCEL_AMOUNT, "예치금 충전 취소", CANCEL_KEY))
                .willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));

        TopupCancelDto result = useCase.cancelTopup(MEMBER_ID, TOPUP_ID);

        assertCanceling(result);
    }

    @Test
    @DisplayName("PG 거절: 되돌리고 400 TOPUP_CANCEL_DECLINED, data에 PG 코드·메시지")
    void cancel_declined() {
        started();
        given(gateway.cancel(PAYMENT_KEY, CANCEL_AMOUNT, "예치금 충전 취소", CANCEL_KEY))
                .willThrow(new PgDeclinedException("EXCEED_MAX_REFUND_DUE", "환불 가능한 기간이 지났습니다.", null));

        assertThatThrownBy(() -> useCase.cancelTopup(MEMBER_ID, TOPUP_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("400-TOPUP_CANCEL_DECLINED");
                    assertThat(e.getData()).isEqualTo(Map.of("code", "EXCEED_MAX_REFUND_DUE", "message", "환불 가능한 기간이 지났습니다."));
                });
        verify(processor).fail(TOPUP_ID);
        verify(processor, never()).complete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("PG 설정 오류: 되돌리고 502 EXTERNAL_SERVICE_ERROR")
    void cancel_configuration() {
        started();
        given(gateway.cancel(PAYMENT_KEY, CANCEL_AMOUNT, "예치금 충전 취소", CANCEL_KEY))
                .willThrow(new PgConfigurationException("UNAUTHORIZED_KEY", null));

        assertThatThrownBy(() -> useCase.cancelTopup(MEMBER_ID, TOPUP_ID))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("502-EXTERNAL_SERVICE_ERROR");
        verify(processor).fail(TOPUP_ID);
    }

    @Test
    @DisplayName("PG 취소 후 반영 실패: 되돌리지 않고 CANCELING으로 응답한다 (스케줄러가 확정)")
    void cancel_completeFails() {
        started();
        given(processor.complete(TOPUP_ID, CANCEL_AMOUNT)).willThrow(new RuntimeException("DB 오류"));

        TopupCancelDto result = useCase.cancelTopup(MEMBER_ID, TOPUP_ID);

        assertCanceling(result);
    }

    private void started() {
        given(processor.start(MEMBER_ID, TOPUP_ID))
                .willReturn(new TopupCancelStartResult(TOPUP_ID, ORDER_ID, PgProvider.TOSS, PAYMENT_KEY, CANCEL_AMOUNT, CANCEL_KEY));
    }

    // 결과 확인 대기: 되돌리지 않음
    private void assertCanceling(TopupCancelDto result) {
        assertThat(result.status()).isEqualTo(TopupStatus.CANCELING);
        assertThat(result.cancelAmount()).isEqualTo(CANCEL_AMOUNT);
        assertThat(result.balance()).isNull();
        verify(processor, never()).fail(anyLong());
    }
}
