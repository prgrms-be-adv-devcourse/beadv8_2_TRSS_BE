package com.backend.boundedContext.payment.app;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.toss.TossPaymentException;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.boundedContext.payment.out.toss.TossTimeoutException;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 충전 승인: Tx1(PROCESSING 선점) → 토스 승인 호출(트랜잭션 밖) → Tx2(결과 반영)
 * 이 클래스는 트랜잭션 없이 흐름만 조립하고, 각 트랜잭션은 PaymentTopupConfirmProcessor에서 실행
 *
 * 토스 결과별 처리
 * - 승인 성공 → 예치금 반영(DONE)
 * - 4xx 오류(카드 거절·한도 초과 등) → FAILED_PG_DECLINED 기록 후 400 TOPUP_PAYMENT_DECLINED
 * - 키 설정 오류(401, INVALID_API_KEY 등) → 우리 설정 문제이므로 FAILED_SYSTEM_ERROR 기록 후 502 EXTERNAL_SERVICE_ERROR
 * - 응답 없음 / 5xx / ALREADY_PROCESSED_PAYMENT → 승인 여부를 알 수 없으므로 PROCESSING 유지, 200 + status PROCESSING
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmTopupUseCase {

    // 이미 승인된 결제를 다시 승인하려 할 때 토스가 주는 코드. 실패가 아니라 승인 완료일 수 있음
    private static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";

    // 시크릿 키·클라이언트 키 설정 오류 코드. 4xx라도 사용자 잘못(카드 거절)이 아니라 우리 설정 문제
    // INVALID_API_KEY: 잘못된 시크릿키 연동 정보(키 세트 불일치 등), UNAUTHORIZED_KEY: 인증되지 않은 키
    private static final Set<String> API_KEY_ERROR_CODES = Set.of("INVALID_API_KEY", "UNAUTHORIZED_KEY");

    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final TossPaymentsClient tossPaymentsClient;

    public TopupConfirmDto confirmTopup(Long memberId, String paymentKey, String orderId, long amount) {
        // Tx1: 금액 검증 후 PROCESSING 선점. 금액이 다르면 실패 기록이 커밋된 뒤 여기서 400
        TopupConfirmStartResult start = paymentTopupConfirmProcessor.start(memberId, orderId, paymentKey, amount);
        if (!start.isStarted()) {
            throw new DomainException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        Long topupId = start.topupId();

        // 트랜잭션 밖: 토스 승인 호출
        TossPaymentResponse toss;
        try {
            toss = tossPaymentsClient.confirm(paymentKey, orderId, amount);
        } catch (TossTimeoutException e) {
            log.warn("토스 승인 응답을 받지 못해 결과 확인 대기로 둡니다. topupId={}", topupId, e);
            return TopupConfirmDto.processing(topupId, orderId, amount);
        } catch (TossPaymentException e) {
            return handleTossError(topupId, orderId, amount, e);
        }

        // Tx2: 예치금 반영. 토스는 이미 승인했으므로 반영에 실패해도 오류로 끝내지 않고 PROCESSING으로 두어 스케줄러가 반영
        try {
            return paymentTopupConfirmProcessor.complete(topupId, toss);
        } catch (RuntimeException e) {
            log.error("토스 승인 후 예치금 반영에 실패해 결과 확인 대기로 둡니다. topupId={}", topupId, e);
            return TopupConfirmDto.processing(topupId, orderId, amount);
        }
    }

    private TopupConfirmDto handleTossError(Long topupId, String orderId, long amount, TossPaymentException e) {
        if (ALREADY_PROCESSED_PAYMENT.equals(e.getCode()) || e.getHttpStatus() >= 500) {
            log.warn("토스 승인 결과가 불확실해 결과 확인 대기로 둡니다. topupId={}, status={}, code={}", topupId, e.getHttpStatus(), e.getCode());
            return TopupConfirmDto.processing(topupId, orderId, amount);
        }

        if (e.getHttpStatus() == 401 || API_KEY_ERROR_CODES.contains(e.getCode())) {
            log.error("토스 API 키 설정 오류로 승인하지 못했습니다. topupId={}, status={}, code={}", topupId, e.getHttpStatus(), e.getCode());
            paymentTopupConfirmProcessor.fail(topupId, TopupStatus.FAILED_SYSTEM_ERROR, "토스 인증에 실패했습니다.");
            throw e;
        }

        // 토스 메시지는 사용자에게 보여 줄 수 있는 문구라 실패 사유와 응답 data에 그대로 사용
        String message = Objects.requireNonNullElse(e.getTossMessage(), PaymentErrorCode.TOPUP_PAYMENT_DECLINED.msg());
        paymentTopupConfirmProcessor.fail(topupId, TopupStatus.FAILED_PG_DECLINED, message);
        throw new DomainException(PaymentErrorCode.TOPUP_PAYMENT_DECLINED,
                Map.of("code", Objects.requireNonNullElse(e.getCode(), "UNKNOWN"), "message", message));
    }
}
