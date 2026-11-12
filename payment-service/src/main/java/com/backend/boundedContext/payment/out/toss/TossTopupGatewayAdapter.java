package com.backend.boundedContext.payment.out.toss;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 토스페이먼츠로 TopupGatewayPort를 구현함
 * 토스 응답(status·method 한글 값·시각)과 오류 코드를 PG와 상관없는 결과·예외로 바꿔, 충전 로직이 토스를 모르게 함
 *
 * 승인 오류 분류
 * - 응답 없음·응답 해석 실패 / 5xx / ALREADY_PROCESSED_PAYMENT → PgResultUnknownException (승인 여부를 알 수 없음)
 * - 401 / INVALID_API_KEY·UNAUTHORIZED_KEY → PgConfigurationException (키 설정 문제)
 * - 그 외 4xx (카드 거절·한도 초과 등) → PgDeclinedException
 *
 * 조회 오류 분류
 * - 404 NOT_FOUND_PAYMENT → PgPaymentNotFoundException (토스에 결제 없음)
 * - 그 외 → PgResultUnknownException (다음에 다시 확인)
 *
 * 취소 오류 분류 (거절로 보면 잔액을 되돌리므로, 취소됐을 가능성이 조금이라도 있으면 결과 모름으로 두고 조회로 확정)
 * - 응답 없음·응답 해석 실패 / 5xx / CANCEL_UNKNOWN_CODES → PgResultUnknownException
 * - 401 / INVALID_API_KEY·UNAUTHORIZED_KEY → PgConfigurationException
 * - 그 외 4xx (취소 가능 기간 지남·부분 취소 불가 수단 등) → PgDeclinedException
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossTopupGatewayAdapter implements TopupGatewayPort {

    // 이미 승인된 결제를 다시 승인하려 할 때 토스가 주는 코드. 실패가 아니라 승인 완료일 수 있음
    private static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";

    // 시크릿 키·클라이언트 키 설정 오류 코드. 4xx라도 사용자 잘못(카드 거절)이 아니라 우리 설정 문제
    // INVALID_API_KEY: 잘못된 시크릿키 연동 정보(키 세트 불일치 등), UNAUTHORIZED_KEY: 인증되지 않은 키
    private static final Set<String> API_KEY_ERROR_CODES = Set.of("INVALID_API_KEY", "UNAUTHORIZED_KEY");

    // 주문번호로 조회했는데 토스에 결제가 없을 때 주는 코드. 승인 요청이 토스에 닿지 않았거나 승인되지 않아 돈이 빠지지 않음
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";

    // 취소가 4xx로 실패해도 취소됐을 수 있는 코드
    // ALREADY_CANCELED_PAYMENT: 이전 요청에서 이미 취소됐을 수 있음, IDEMPOTENT_REQUEST_PROCESSING·ALREADY_PROCESSING_REQUEST·FORBIDDEN_CONSECUTIVE_REQUEST: 앞선 요청이 처리 중일 수 있음
    // PROVIDER_ERROR: 결제사 일시 오류라 처리 여부가 확실하지 않음
    private static final Set<String> CANCEL_UNKNOWN_CODES = Set.of(
            "ALREADY_CANCELED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING", "ALREADY_PROCESSING_REQUEST", "FORBIDDEN_CONSECUTIVE_REQUEST", "PROVIDER_ERROR"
    );

    // 토스 결제 status → PG 결제 상태. 여기 없는 값(READY·IN_PROGRESS·WAITING_FOR_DEPOSIT 등)은 PENDING
    private static final Map<String, PgPaymentStatus> STATUSES = Map.of(
            "DONE", PgPaymentStatus.APPROVED,
            "ABORTED", PgPaymentStatus.NOT_APPROVED,
            "EXPIRED", PgPaymentStatus.NOT_APPROVED,
            "CANCELED", PgPaymentStatus.CANCELED,
            "PARTIAL_CANCELED", PgPaymentStatus.PARTIALLY_CANCELED
    );

    // 토스 결제 method(한글 값) → 결제 수단. 충전 결제창에 있는 수단만 두고, 여기 없는 값은 OTHER
    // 토스는 간편결제 종류(토스페이·페이코 등)를 모두 "간편결제"로 줌
    private static final Map<String, PgPaymentMethod> METHODS = Map.of(
            "카드", PgPaymentMethod.CARD,
            "간편결제", PgPaymentMethod.EASY_PAY,
            "계좌이체", PgPaymentMethod.TRANSFER
    );

    private final TossPaymentsClient tossPaymentsClient;

    @Override
    public PgProvider provider() {
        return PgProvider.TOSS;
    }

    @Override
    public PgPaymentResult confirm(String paymentKey, String orderId, long amount) {
        try {
            return toResult(tossPaymentsClient.confirm(paymentKey, orderId, amount));
        } catch (TossTimeoutException e) {
            throw new PgResultUnknownException(e);
        } catch (TossPaymentException e) {
            if (ALREADY_PROCESSED_PAYMENT.equals(e.getCode()) || e.getHttpStatus() >= 500) {
                throw new PgResultUnknownException(e);
            }
            if (e.getHttpStatus() == 401 || API_KEY_ERROR_CODES.contains(e.getCode())) {
                throw new PgConfigurationException(e.getCode(), e);
            }
            throw new PgDeclinedException(e.getCode(), e.getTossMessage(), e);
        }
    }

    @Override
    public PgPaymentResult getPaymentByOrderId(String orderId) {
        try {
            return toResult(tossPaymentsClient.getPaymentByOrderId(orderId));
        } catch (TossTimeoutException e) {
            throw new PgResultUnknownException(e);
        } catch (TossPaymentException e) {
            if (e.getHttpStatus() == 404 && NOT_FOUND_PAYMENT.equals(e.getCode())) {
                throw new PgPaymentNotFoundException(e);
            }
            throw new PgResultUnknownException(e);
        }
    }

    @Override
    public PgPaymentResult cancel(String paymentKey, long cancelAmount, String reason, String idempotencyKey) {
        try {
            return toResult(tossPaymentsClient.cancel(paymentKey, cancelAmount, reason, idempotencyKey));
        } catch (TossTimeoutException e) {
            throw new PgResultUnknownException(e);
        } catch (TossPaymentException e) {
            if (CANCEL_UNKNOWN_CODES.contains(e.getCode()) || e.getHttpStatus() >= 500) {
                throw new PgResultUnknownException(e);
            }
            if (e.getHttpStatus() == 401 || API_KEY_ERROR_CODES.contains(e.getCode())) {
                throw new PgConfigurationException(e.getCode(), e);
            }
            throw new PgDeclinedException(e.getCode(), e.getTossMessage(), e);
        }
    }

    private static PgPaymentResult toResult(TossPaymentResponse toss) {
        return new PgPaymentResult(
                toss.paymentKey(),
                toss.orderId(),
                STATUSES.getOrDefault(toss.status(), PgPaymentStatus.PENDING),
                toss.status(),
                toss.totalAmount(),
                toss.canceledAmount(),
                toMethod(toss),
                toss.approvedAtKst()
        );
    }

    // OTHER로 바꾸면 원래 값이 사라지므로, 결제창에 없는 수단이 들어오면 토스 값을 로그로 남김
    private static PgPaymentMethod toMethod(TossPaymentResponse toss) {
        if (toss.method() == null) return PgPaymentMethod.OTHER;

        PgPaymentMethod method = METHODS.get(toss.method());
        if (method == null) {
            log.warn("결제창에 없는 토스 결제 수단입니다. orderId={}, method={}", toss.orderId(), toss.method());
            return PgPaymentMethod.OTHER;
        }
        return method;
    }
}
