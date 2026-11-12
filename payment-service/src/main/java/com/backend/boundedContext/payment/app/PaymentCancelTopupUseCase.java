package com.backend.boundedContext.payment.app;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupCancelDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 충전 취소: Tx1(CANCELING, 잔액 먼저 차감) → PG 취소 호출(트랜잭션 밖) → Tx2(결과 반영)
 * 남은 금액 전부를 결제한 PG로 취소함. 일부를 쓴 충전 건은 남은 금액만 부분 취소
 * 이 클래스는 트랜잭션 없이 흐름만 조립하고, 각 트랜잭션은 PaymentTopupCancelProcessor에서 실행
 *
 * PG 결과별 처리
 * - 취소 성공 → 취소 금액 확정 (전부 취소되면 CANCELED, 아니면 DONE)
 * - 거절(취소 가능 기간 지남·부분 취소 불가 수단 등) → 잔액·남은 금액을 되돌리고 400 TOPUP_CANCEL_DECLINED
 * - 설정 오류(API 키 등) → 잔액·남은 금액을 되돌리고 502 EXTERNAL_SERVICE_ERROR
 * - 결과 모름(응답 없음·5xx 등) → 취소됐을 수 있으므로 되돌리지 않고 CANCELING 유지, 200 + status CANCELING
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelTopupUseCase {

    // PG에 남기는 취소 사유
    private static final String CANCEL_REASON = "예치금 충전 취소";

    private final PaymentTopupCancelProcessor paymentTopupCancelProcessor;
    private final TopupGateways topupGateways;
    private final PaymentMemberPort memberPort;

    public TopupCancelDto cancelTopup(Long memberId, Long topupId) {
        // 탈퇴한 회원은 남은 Access Token으로 취소할 수 없게 401
        memberPort.checkActiveMember(memberId);

        // Tx1: CANCELING으로 바꾸고 잔액에서 취소 금액을 먼저 뺌
        TopupCancelStartResult start = paymentTopupCancelProcessor.start(memberId, topupId);

        // 트랜잭션 밖: 충전 건의 PG로 취소 호출
        try {
            topupGateways.get(start.pgProvider()).cancel(start.paymentKey(), start.cancelAmount(), CANCEL_REASON, start.idempotencyKey());
        } catch (PgResultUnknownException e) {
            log.warn("PG 취소 결과를 알 수 없어 결과 확인 대기로 둡니다. topupId={}", topupId, e);
            return TopupCancelDto.canceling(start);
        } catch (PgConfigurationException e) {
            log.error("PG 연동 설정 오류로 취소하지 못했습니다. topupId={}, code={}", topupId, e.getCode(), e);
            paymentTopupCancelProcessor.fail(topupId);
            throw e;
        } catch (PgDeclinedException e) {
            // PG 메시지는 사용자에게 보여 줄 수 있는 문구라 응답 data에 그대로 사용
            String message = Objects.requireNonNullElse(e.getPgMessage(), PaymentErrorCode.TOPUP_CANCEL_DECLINED.msg());
            paymentTopupCancelProcessor.fail(topupId);
            throw new DomainException(PaymentErrorCode.TOPUP_CANCEL_DECLINED, Map.of("code", Objects.requireNonNullElse(e.getCode(), "UNKNOWN"), "message", message));
        }

        // Tx2: 취소 확정. PG는 이미 취소했으므로 반영에 실패해도 오류로 끝내지 않고 CANCELING으로 두어 스케줄러가 반영
        try {
            return paymentTopupCancelProcessor.complete(topupId, start.cancelAmount());
        } catch (RuntimeException e) {
            log.error("PG 취소 후 반영에 실패해 결과 확인 대기로 둡니다. topupId={}", topupId, e);
            return TopupCancelDto.canceling(start);
        }
    }
}
