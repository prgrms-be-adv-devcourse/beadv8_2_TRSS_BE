package com.backend.boundedContext.payment.app;

import java.util.Map;
import java.util.Objects;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgDeclinedException;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 충전 승인: Tx1(PROCESSING 선점) → PG 승인 호출(트랜잭션 밖) → Tx2(결과 반영)
 * 이 클래스는 트랜잭션 없이 흐름만 조립하고, 각 트랜잭션은 PaymentTopupConfirmProcessor에서 실행
 * 승인은 충전 건에 기록된 PG로 보냄 (TopupGateways)
 * 이미 승인 중이거나 승인된 충전 건에 같은 요청이 다시 오면 PG를 부르지 않고 처음 결과를 돌려줌 (Tx1에서 판단)
 *
 * PG 결과별 처리
 * - 승인 성공 → 예치금 반영(DONE)
 * - 거절(카드 거절·한도 초과 등) → FAILED_PG_DECLINED 기록 후 400 TOPUP_PAYMENT_DECLINED
 * - 설정 오류(API 키 등) → 우리 설정 문제이므로 FAILED_SYSTEM_ERROR 기록 후 502 EXTERNAL_SERVICE_ERROR
 * - 결과 모름(응답 없음·5xx·이미 처리된 결제) → 그 자리에서 PG에 조회 (confirmOrRecover)
 *   - 승인됨 → 승인 성공과 같게 반영
 *   - 거래 없음(처리되지 않음)이고 PG가 두 번째 승인을 거절하는 PG(헥토) → 승인을 한 번만 다시 보냄
 *   - 그 밖(조회도 결과 모름, 진행 중 등) → 승인 여부를 알 수 없으므로 PROCESSING 유지, 200 + status PROCESSING (대사가 정리)
 *   대사는 인증만 된 건을 다시 승인하지 않음. 여기서는 승인 요청 직후 처리되지 않은 것을 조회로 확인한 경우에만 한 번 다시 보냄
 * - 승인됐지만 허용하지 않은 결제 수단 → 예치금에 반영하지 않고 PG 전액 취소, 400 TOPUP_METHOD_NOT_ALLOWED
 *   (취소 결과를 모르거나 실패하면 METHOD_CANCELING으로 남겨 대사가 다시 취소)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmTopupUseCase {

    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final TopupGateways topupGateways;
    private final PaymentTopupMethodCanceller paymentTopupMethodCanceller;
    private final PaymentMemberPort memberPort;

    public TopupConfirmDto confirmTopup(Long memberId, String paymentKey, String orderId, long amount) {
        // 탈퇴한 회원은 남은 Access Token으로 승인할 수 없게 401. PG 승인 전이라 결제가 확정되지 않음
        memberPort.checkActiveMember(memberId);

        // Tx1: 금액 검증 후 PROCESSING 선점. 금액이 다르면 실패 기록이 커밋된 뒤 여기서 400
        TopupConfirmStartResult start = paymentTopupConfirmProcessor.start(memberId, orderId, paymentKey, amount);
        if (start.isReplayed()) {
            log.info("같은 충전 승인 요청이 다시 와 처음 결과를 돌려줍니다. topupId={}, status={}", start.topupId(), start.replay().status());
            return start.replay();
        }
        if (!start.isStarted()) {
            throw new DomainException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        Long topupId = start.topupId();

        // 트랜잭션 밖: 충전 건의 PG로 승인 호출. 결과를 모르면 바로 조회하고, 처리되지 않았으면 한 번 다시 승인
        PgPaymentResult result;
        try {
            result = confirmOrRecover(topupGateways.get(start.pgProvider()), start.payment(), topupId, paymentKey, orderId, amount);
        } catch (PgResultUnknownException e) {
            log.warn("PG 승인 결과를 알 수 없어 결과 확인 대기로 둡니다. topupId={}", topupId, e);
            return TopupConfirmDto.processing(topupId, orderId, amount);
        } catch (PgConfigurationException e) {
            log.error("PG 연동 설정 오류로 승인하지 못했습니다. topupId={}, code={}", topupId, e.getCode(), e);
            paymentTopupConfirmProcessor.fail(topupId, TopupStatus.FAILED_SYSTEM_ERROR, "PG 인증에 실패했습니다.");
            throw e;
        } catch (PgDeclinedException e) {
            // PG 메시지는 사용자에게 보여 줄 수 있는 문구라 실패 사유와 응답 data에 그대로 사용
            String message = Objects.requireNonNullElse(e.getPgMessage(), PaymentErrorCode.TOPUP_PAYMENT_DECLINED.msg());
            paymentTopupConfirmProcessor.fail(topupId, TopupStatus.FAILED_PG_DECLINED, message);
            throw new DomainException(PaymentErrorCode.TOPUP_PAYMENT_DECLINED, Map.of("code", Objects.requireNonNullElse(e.getCode(), "UNKNOWN"), "message", message));
        }

        // 허용하지 않은 결제 수단이면 반영하지 않고 PG 전액 취소
        if (!PaymentTopupConfirmProcessor.isAllowedMethod(result.method())) {
            paymentTopupMethodCanceller.cancelApproved(topupId, result.method());
            throw new DomainException(PaymentErrorCode.TOPUP_METHOD_NOT_ALLOWED);
        }

        // Tx2: 예치금 반영. PG는 이미 승인했으므로 반영에 실패해도 오류로 끝내지 않고 PROCESSING으로 두어 스케줄러가 반영
        try {
            return paymentTopupConfirmProcessor.complete(topupId, result);
        } catch (RuntimeException e) {
            log.error("PG 승인 후 예치금 반영에 실패해 결과 확인 대기로 둡니다. topupId={}", topupId, e);
            return TopupConfirmDto.processing(topupId, orderId, amount);
        }
    }

    /**
     * PG 승인. 결과를 모르면(응답 없음·연결 끊김 등) 그 자리에서 조회해 결과를 정함
     * - 조회가 승인됨이면 그 결과로 반영
     * - 거래 없음이고 재승인할 수 있는 PG면 승인을 한 번 다시 보냄 (거절이면 PgDeclinedException, 다시 결과 모름이면 PgResultUnknownException)
     * - 그 밖이면 처음의 결과 모름을 다시 던져 PROCESSING으로 둠
     */
    private PgPaymentResult confirmOrRecover(TopupGatewayPort gateway, PgPaymentRef payment, Long topupId, String paymentKey, String orderId, long amount) {
        try {
            return gateway.confirm(paymentKey, orderId, amount);
        } catch (PgResultUnknownException unknown) {
            log.warn("PG 승인 결과를 알 수 없어 바로 조회합니다. topupId={}, 원인={}", topupId, NestedExceptionUtils.getMostSpecificCause(unknown).getMessage());
            try {
                PgPaymentResult found = gateway.getPayment(payment);
                if (found.isApproved()) return found;
                throw unknown;
            } catch (PgPaymentNotFoundException notFound) {
                if (!gateway.canRetryConfirm()) throw unknown;
                log.warn("PG에 승인 요청이 처리되지 않은 것을 확인해 한 번 다시 승인합니다. topupId={}", topupId);
                return gateway.confirm(paymentKey, orderId, amount);
            } catch (PgConfigurationException e) {
                // 조회만 설정 오류로 실패해도 승인은 됐을 수 있어 실패로 기록하지 않고 PROCESSING으로 둠
                throw unknown;
            }
        }
    }
}
