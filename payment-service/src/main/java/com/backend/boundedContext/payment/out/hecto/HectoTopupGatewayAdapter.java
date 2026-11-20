package com.backend.boundedContext.payment.out.hecto;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgCancelRequest;
import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 헥토파이낸셜(신용카드)로 TopupGatewayPort를 구현함
 * 헥토는 성공·실패를 HTTP 200의 outStatCd(0021 성공, 0031 실패)와 outRsltCd로 알려 주므로, 응답 코드를 PG와 상관없는 결과·예외로 바꿈
 * 신용카드만 연동하므로 결제 수단은 항상 CARD
 *
 * 공통
 * - 응답 없음·HTTP 오류·해석할 수 없는 응답·승인 응답 해시 불일치 → PgResultUnknownException
 * - 암호화 키 미설정 등 요청을 만들지 못함 → PgConfigurationException (헥토에 요청이 가지 않음)
 *
 * 승인: 0021 → APPROVED / 0031 → PgDeclinedException (승인되지 않음이 확정됨)
 *
 * 조회 (성공 응답의 outRsltCd)
 * - 0000 정상 → 취소 금액이 없으면 APPROVED, 있으면 PARTIALLY_CANCELED
 * - 0001 취소 → 전액이면 CANCELED, 아니면 PARTIALLY_CANCELED
 * - 0009 결제 실패 → NOT_APPROVED
 * - 그 외 → PENDING (다음에 다시 확인)
 * - 실패 ST06(거래 없음) → PgPaymentNotFoundException (결제창에서 인증하지 않은 주문)
 * - 그 외 실패 → PgResultUnknownException (요청 값·설정 문제일 수 있어 다음에 다시 확인)
 *
 * 취소 (거절로 보면 잔액을 되돌리므로, 취소됐을 가능성이 있으면 결과 모름으로 두고 조회로 확정)
 * - 0021 → 성공 (남은 금액 0이면 CANCELED, 아니면 PARTIALLY_CANCELED)
 * - ST25(이미 취소 완료)·ST57(취소 금액 초과) → PgResultUnknownException (앞선 요청이나 상점관리자에서 이미 취소됐을 수 있음)
 * - 그 외 실패 → PgDeclinedException
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HectoTopupGatewayAdapter implements TopupGatewayPort {

    // 조회 결과 코드
    private static final String INQUIRY_NORMAL = "0000";
    private static final String INQUIRY_CANCELED = "0001";
    private static final String INQUIRY_FAILED = "0009";

    // 조회했는데 헥토에 거래가 없을 때 주는 코드
    private static final String NOT_FOUND = "ST06";

    // 같은 인증 거래번호로 승인을 다시 보냈을 때 주는 코드(중복결제). 앞선 승인이 처리됐다는 뜻이라 결과 모름으로 봄
    private static final String DUPLICATE_PAYMENT = "ST50";

    // 취소가 실패해도 이미 취소됐을 수 있는 코드
    // ST25: 이미 전액 취소된 거래, ST57: 취소 금액이 남은 금액보다 큼 (일부가 이미 취소됐을 수 있음)
    private static final Set<String> CANCEL_UNKNOWN_CODES = Set.of("ST25", "ST57");

    // 결제창을 닫거나 취소해 cancUrl로 돌아온 경우. 결제창 결과를 받는 엔드포인트가 이 코드로 프론트에 넘김
    private static final String USER_CANCEL = "USER_CANCEL";

    // 요청을 만들지 못했을 때(키 미설정 등) PgConfigurationException에 남기는 코드
    private static final String CONFIGURATION_ERROR = "HECTO_CONFIGURATION_ERROR";

    private final HectoClient hectoClient;
    private final HectoCheckout hectoCheckout;

    @Override
    public PgProvider provider() {
        return PgProvider.HECTO;
    }

    // 결제창 요청 값. 키가 없어 금액을 암호화하지 못하면 설정 오류
    @Override
    public Optional<PgCheckout> checkout(PgPaymentRef payment) {
        try {
            return Optional.of(hectoCheckout.create(payment));
        } catch (IllegalStateException e) {
            throw new PgConfigurationException(CONFIGURATION_ERROR, e);
        }
    }

    @Override
    public boolean isUserCancel(String failCode) {
        return USER_CANCEL.equals(failCode);
    }

    // paymentKey: 결제창 인증 결과(nextUrl)로 받은 헥토 거래번호. 승인 후에도 같은 번호를 씀
    @Override
    public PgPaymentResult confirm(String paymentKey, String orderId, long amount) {
        HectoResponse response = call(() -> hectoClient.approve(orderId, paymentKey, amount, LocalDateTime.now()));

        if (!response.isSuccess()) {
            // 중복결제는 거절이 아님. 앞선 승인이 처리돼 카드는 결제됐으므로 거절로 기록하면 돈만 빠지고 충전은 실패로 끝남
            if (DUPLICATE_PAYMENT.equals(response.outRsltCd())) {
                throw new PgResultUnknownException(failure(response));
            }
            throw declined(response);
        }
        return new PgPaymentResult(response.trdNo(), orderId, PgPaymentStatus.APPROVED, response.outRsltCd(),
                response.amount(), 0, PgPaymentMethod.CARD, response.transactedAt());
    }

    /**
     * 거래 조회. 원거래 일자는 결제 요청 날짜로 보냄
     * 거래가 없고(ST06) 승인 요청 날짜가 다르면(자정을 넘긴 충전 건) 그 날짜로 한 번 더 조회한 뒤에 거래 없음으로 봄
     * (테스트베드는 원거래 일자 ±1일까지 찾아 주지만 문서에 없는 동작이라 기대지 않음)
     */
    // 같은 인증 거래번호의 두 번째 승인은 헥토가 ST50으로 거절해(테스트베드 확인) 다시 보내도 이중 결제가 생기지 않음
    @Override
    public boolean canRetryConfirm() {
        return true;
    }

    @Override
    public PgPaymentResult getPayment(PgPaymentRef payment) {
        HectoResponse response = inquiry(payment, payment.requestedAt());
        if (NOT_FOUND.equals(response.outRsltCd()) && isOtherDay(payment.requestedAt(), payment.confirmRequestedAt())) {
            response = inquiry(payment, payment.confirmRequestedAt());
        }

        if (!response.isSuccess()) {
            if (NOT_FOUND.equals(response.outRsltCd())) {
                throw new PgPaymentNotFoundException(failure(response));
            }
            throw new PgResultUnknownException(failure(response));
        }

        long totalAmount = Objects.requireNonNullElse(response.amount(), payment.amount());
        long canceledAmount = Objects.requireNonNullElse(response.canceledAmount(), 0L);
        String paymentKey = response.trdNo() == null || response.trdNo().isBlank() ? payment.paymentKey() : response.trdNo();

        // 조회 응답에는 승인 일시가 없어 null (반영할 때 반영 시각을 씀)
        return new PgPaymentResult(paymentKey, payment.orderId(), toStatus(response, payment, canceledAmount), response.outRsltCd(),
                totalAmount, canceledAmount, PgPaymentMethod.CARD, null);
    }

    @Override
    public PgPaymentResult cancel(PgCancelRequest request) {
        PgPaymentRef payment = request.payment();
        HectoResponse response = call(() -> hectoClient.cancel(payment.orderId(), payment.paymentKey(), request.attempt(), request.cancelAmount(), LocalDateTime.now()));

        if (!response.isSuccess()) {
            if (CANCEL_UNKNOWN_CODES.contains(response.outRsltCd())) {
                throw new PgResultUnknownException(failure(response));
            }
            throw declined(response);
        }

        // 남은 금액이 없으면 전액 취소됨. 취소 합계는 충전 금액 - 남은 금액
        long balanceAmount = Objects.requireNonNullElse(response.balanceAmount(), 0L);
        PgPaymentStatus status = balanceAmount == 0 ? PgPaymentStatus.CANCELED : PgPaymentStatus.PARTIALLY_CANCELED;

        return new PgPaymentResult(payment.paymentKey(), payment.orderId(), status, response.outRsltCd(),
                payment.amount(), payment.amount() - balanceAmount, PgPaymentMethod.CARD, null);
    }

    private HectoResponse inquiry(PgPaymentRef payment, LocalDateTime orgTradeAt) {
        return call(() -> hectoClient.inquiry(payment.orderId(), payment.paymentKey(), payment.amount(), orgTradeAt, LocalDateTime.now()));
    }

    private static boolean isOtherDay(LocalDateTime requestedAt, LocalDateTime confirmRequestedAt) {
        return confirmRequestedAt != null && !confirmRequestedAt.toLocalDate().equals(requestedAt.toLocalDate());
    }

    // 조회 결과 코드 → PG 결제 상태. 알 수 없는 코드는 원래 값을 로그로 남기고 PENDING
    private static PgPaymentStatus toStatus(HectoResponse response, PgPaymentRef payment, long canceledAmount) {
        return switch (response.outRsltCd()) {
            case INQUIRY_NORMAL -> canceledAmount == 0 ? PgPaymentStatus.APPROVED : PgPaymentStatus.PARTIALLY_CANCELED;
            case INQUIRY_CANCELED -> canceledAmount >= payment.amount() ? PgPaymentStatus.CANCELED : PgPaymentStatus.PARTIALLY_CANCELED;
            case INQUIRY_FAILED -> PgPaymentStatus.NOT_APPROVED;
            case null, default -> {
                log.warn("알 수 없는 헥토 조회 결과 코드입니다. orderId={}, outRsltCd={}, outRsltMsg={}", payment.orderId(), response.outRsltCd(), response.outRsltMsg());
                yield PgPaymentStatus.PENDING;
            }
        };
    }

    // 헥토 호출. 처리 여부를 알 수 없으면 결과 모름, 요청을 만들지 못했으면(키 미설정 등) 설정 오류
    private static HectoResponse call(Supplier<HectoResponse> request) {
        try {
            return request.get();
        } catch (HectoResultUnknownException e) {
            throw new PgResultUnknownException(e);
        } catch (IllegalStateException e) {
            throw new PgConfigurationException(CONFIGURATION_ERROR, e);
        }
    }

    private static PgDeclinedException declined(HectoResponse response) {
        return new PgDeclinedException(response.outRsltCd(), response.outRsltMsg(), failure(response));
    }

    // 실패 응답을 예외 원인으로 남겨, 로그에서 헥토 코드·메시지를 볼 수 있게 함
    private static IllegalStateException failure(HectoResponse response) {
        return new IllegalStateException("헥토 실패 응답: outStatCd=" + response.outStatCd()
                + ", outRsltCd=" + response.outRsltCd() + ", outRsltMsg=" + response.outRsltMsg());
    }
}
