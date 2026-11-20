package com.backend.boundedContext.payment.app;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.out.pg.PgCancelRequest;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.TopupGateways;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 허용하지 않은 결제 수단으로 승인된 충전 건을 PG에 전액 취소함 (승인 응답·PROCESSING 대사·METHOD_CANCELING 대사가 함께 씀)
 * 트랜잭션 없이 흐름만 조립: Tx1(METHOD_CANCELING 시작 또는 회차 +1) → PG 전액 취소(트랜잭션 밖) → 성공하면 Tx2(FAILED_METHOD_NOT_ALLOWED 확정)
 * 취소가 실패해도 예외를 던지지 않음: 충전 건이 METHOD_CANCELING으로 남아 METHOD_CANCELING 대사가 다시 취소함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTopupMethodCanceller {

    // PG에 남기는 취소 사유
    private static final String CANCEL_REASON = "충전에 허용하지 않은 결제 수단";

    // 관리자가 다시 요청해 취소됐을 때의 충전 건 실패 사유
    static final String MANUAL_RETRY_REASON = "허용하지 않은 결제 수단이라 관리자 요청으로 PG에서 결제를 취소했습니다.";

    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final TopupGateways topupGateways;

    /**
     * PG 승인을 확인한 PROCESSING 충전 건의 첫 취소
     * PROCESSING이 아니면(승인 응답과 대사 중 다른 쪽이 먼저 처리) PG 취소를 보내지 않음
     */
    public void cancelApproved(Long topupId, PgPaymentMethod method) {
        log.warn("허용하지 않은 결제 수단으로 충전이 승인돼 PG에 전액 취소합니다. topupId={}, method={}", topupId, method);
        try {
            paymentTopupConfirmProcessor.startMethodCancel(topupId)
                    .ifPresent(start -> cancelAndComplete(start, method));
        } catch (RuntimeException e) {
            log.error("허용하지 않은 결제 수단의 PG 취소를 끝내지 못해 결과 확인 대기로 둡니다. topupId={}", topupId, e);
        }
    }

    /**
     * 취소되지 않은 METHOD_CANCELING 충전 건을 회차를 늘려 다시 취소하고, 확정했는지 돌려줌
     * 그사이 회차가 바뀌었거나 이미 확정됐으면 PG 취소를 보내지 않고 false
     */
    public boolean retry(Long topupId, int attempt, PgPaymentMethod method) {
        try {
            return paymentTopupConfirmProcessor.retryMethodCancel(topupId, attempt)
                    .map(start -> {
                        cancelAndComplete(start, method);
                        return true;
                    })
                    .orElse(false);
        } catch (RuntimeException e) {
            // 회차마다 반복될 수 있어 스택 대신 가장 안쪽 원인 메시지만 남김
            log.warn("허용하지 않은 결제 수단의 PG 취소를 다시 요청했지만 끝내지 못했습니다. topupId={}, 원인={}", topupId, NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return false;
        }
    }

    /**
     * 관리자가 고른 다시 요청: 회차를 늘린 값(start)으로 PG 전액 취소를 보내고, 확정했는지 돌려줌
     * 실패해도 예외를 던지지 않음 (METHOD_CANCELING으로 남아 대사가 다시 확인함)
     */
    public boolean retryManually(TopupMethodCancelStart start) {
        try {
            cancelAndComplete(start, MANUAL_RETRY_REASON);
            return true;
        } catch (RuntimeException e) {
            log.warn("관리자가 다시 요청한 허용하지 않은 결제 수단의 PG 취소를 끝내지 못했습니다. 대사가 다시 확인합니다. topupId={}, 원인={}", start.topupId(), NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return false;
        }
    }

    private void cancelAndComplete(TopupMethodCancelStart start, PgPaymentMethod method) {
        cancelAndComplete(start, completedReason(method));
    }

    private void cancelAndComplete(TopupMethodCancelStart start, String reason) {
        topupGateways.get(start.pgProvider()).cancel(new PgCancelRequest(start.payment(), start.amount(), CANCEL_REASON, start.attempt(), start.idempotencyKey()));
        paymentTopupConfirmProcessor.completeMethodCancel(start.topupId(), start.attempt(), reason);
    }

    // 충전 건 실패 사유. 대사가 PG 조회로 취소를 확인했을 때도 같은 문구를 씀
    static String completedReason(PgPaymentMethod method) {
        return "허용하지 않은 결제 수단이라 PG에서 결제를 취소했습니다. (" + method + ")";
    }
}
