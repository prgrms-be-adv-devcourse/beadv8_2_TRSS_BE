package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

class PaymentConfirmTopupUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long TOPUP_ID = 7L;
    private static final String PAYMENT_KEY = "pk_1";
    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final long AMOUNT = 20_000;

    private final PaymentTopupConfirmProcessor processor = mock(PaymentTopupConfirmProcessor.class);
    private final TopupGateways topupGateways = mock(TopupGateways.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentMemberPort memberPort = mock(PaymentMemberPort.class);
    private final PaymentConfirmTopupUseCase useCase = new PaymentConfirmTopupUseCase(processor, topupGateways, memberPort);

    private final PgPaymentResult approved =
            new PgPaymentResult(PAYMENT_KEY, ORDER_ID, PgPaymentStatus.APPROVED, AMOUNT, PgPaymentMethod.CARD, null);

    @BeforeEach
    void setUp() {
        given(topupGateways.get(PgProvider.TOSS)).willReturn(gateway);
    }

    @Test
    @DisplayName("승인 성공: 선점 → 충전 건의 PG로 승인 → 예치금 반영 결과를 돌려준다")
    void confirm_success() {
        started();
        given(gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).willReturn(approved);
        TopupConfirmDto done = new TopupConfirmDto(TOPUP_ID, ORDER_ID, TopupStatus.DONE, AMOUNT, 25_000L);
        given(processor.complete(TOPUP_ID, approved)).willReturn(done);

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(result).isEqualTo(done);
        verify(topupGateways).get(PgProvider.TOSS);
    }

    @Test
    @DisplayName("탈퇴한 회원: 충전 건을 선점하지 않고 PG도 호출하지 않으며 401 UNAUTHORIZED (탈퇴 후 남은 Access Token)")
    void confirm_withdrawnMember() {
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberPort).checkActiveMember(MEMBER_ID);

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("401-UNAUTHORIZED");

        verifyNoInteractions(processor, gateway);
    }

    @Test
    @DisplayName("금액 불일치: PG를 호출하지 않고 400 AMOUNT_MISMATCH")
    void confirm_amountMismatch() {
        given(processor.start(MEMBER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                .willReturn(new TopupConfirmStartResult(TOPUP_ID, PgProvider.TOSS, TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH));

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-AMOUNT_MISMATCH");
        verify(gateway, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("PG 결과 모름(응답 없음·5xx·이미 처리된 결제): 실패로 단정하지 않고 PROCESSING으로 응답한다")
    void confirm_resultUnknown() {
        started();
        given(gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertProcessing(result);
    }

    @Test
    @DisplayName("PG 거절: FAILED_PG_DECLINED로 기록하고 400 TOPUP_PAYMENT_DECLINED, data에 PG 코드·메시지")
    void confirm_declined() {
        started();
        given(gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new PgDeclinedException("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.", null));

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("400-TOPUP_PAYMENT_DECLINED");
                    assertThat(e.getData()).isEqualTo(Map.of(
                            "code", "REJECT_CARD_PAYMENT", "message", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));
                });
        verify(processor).fail(TOPUP_ID, TopupStatus.FAILED_PG_DECLINED, "한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
    }

    @Test
    @DisplayName("PG 거절인데 메시지가 없으면 기본 문구로 기록하고 응답한다")
    void confirm_declinedWithoutMessage() {
        started();
        given(gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new PgDeclinedException(null, null, null));
        String defaultMessage = PaymentErrorCode.TOPUP_PAYMENT_DECLINED.msg();

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getData()).isEqualTo(Map.of("code", "UNKNOWN", "message", defaultMessage)));
        verify(processor).fail(TOPUP_ID, TopupStatus.FAILED_PG_DECLINED, defaultMessage);
    }

    @Test
    @DisplayName("PG 설정 오류(API 키 등): 카드 거절이 아니라 FAILED_SYSTEM_ERROR로 기록하고 502 EXTERNAL_SERVICE_ERROR")
    void confirm_configurationError() {
        started();
        given(gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willThrow(new PgConfigurationException("INVALID_API_KEY", null));

        assertThatThrownBy(() -> useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("502-EXTERNAL_SERVICE_ERROR");
        verify(processor).fail(TOPUP_ID, TopupStatus.FAILED_SYSTEM_ERROR, "PG 인증에 실패했습니다.");
        verify(processor, never()).fail(anyLong(), eq(TopupStatus.FAILED_PG_DECLINED), any());
    }

    @Test
    @DisplayName("PG 승인 후 예치금 반영 실패: 오류로 끝내지 않고 PROCESSING으로 응답한다")
    void confirm_completeFails() {
        started();
        given(gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).willReturn(approved);
        given(processor.complete(TOPUP_ID, approved)).willThrow(new RuntimeException("DB 오류"));

        TopupConfirmDto result = useCase.confirmTopup(MEMBER_ID, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertProcessing(result);
    }

    private void started() {
        given(processor.start(MEMBER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                .willReturn(new TopupConfirmStartResult(TOPUP_ID, PgProvider.TOSS, TopupConfirmStartResult.Outcome.STARTED));
    }

    // 결과 확인 대기: 실패 기록도 반영도 하지 않음
    private void assertProcessing(TopupConfirmDto result) {
        assertThat(result.status()).isEqualTo(TopupStatus.PROCESSING);
        assertThat(result.topupId()).isEqualTo(TOPUP_ID);
        assertThat(result.balance()).isNull();
        verify(processor, never()).fail(anyLong(), any(), any());
    }
}
