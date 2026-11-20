package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.AdminTopupManualResolveDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualResolveAction;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 충전 수동 확인 정리: 대사가 자동으로 정할 수 없어 표시한 건을 관리자가 PG 조회 결과를 보고 고른 방법으로 정리함
 * 반영은 대사와 같은 트랜잭션 메서드를 쓰고, 표시 확인·상태 확인은 잠금 안에서 함 (두 관리자가 동시에 정리해도 한 번만 반영됨)
 * 감사 로그 기능이 없어 누가 어떻게 정리했는지 INFO 로그로 남김
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResolveTopupManualCheckUseCase {

    // 관리자가 PG 전액 취소를 확인해 확정했을 때의 충전 건 실패 사유
    static final String CONFIRM_METHOD_CANCEL_REASON = "허용하지 않은 결제 수단이라 관리자가 PG 취소를 확인해 확정했습니다.";

    private final TopupRepository topupRepository;
    private final PaymentTopupCancelProcessor paymentTopupCancelProcessor;
    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final PaymentTopupMethodCanceller paymentTopupMethodCanceller;

    public AdminTopupManualResolveDto resolve(Long adminId, Long topupId, TopupManualResolveAction action) {
        // 로그에 남길 정리 전 상태·사유 (잠그지 않고 읽음)
        Topup before = topupRepository.findById(topupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));

        TopupStatus status = switch (action) {
            case CONFIRM_CANCEL, RESTORE -> paymentTopupCancelProcessor.resolveManualCheck(topupId, action);
            case CONFIRM_METHOD_CANCEL -> paymentTopupConfirmProcessor.confirmMethodCancelManually(topupId, CONFIRM_METHOD_CANCEL_REASON);
            case RETRY_METHOD_CANCEL -> paymentTopupMethodCanceller.retryManually(paymentTopupConfirmProcessor.startManualMethodCancelRetry(topupId)) ? TopupStatus.FAILED_METHOD_NOT_ALLOWED : TopupStatus.METHOD_CANCELING;
        };

        log.info("관리자가 수동 확인 충전 건을 정리했습니다. adminId={}, topupId={}, action={}, 사유={}, 상태 {} → {}", adminId, topupId, action, before.getManualCheckReason(), before.getStatus(), status);
        return new AdminTopupManualResolveDto(topupId, action, status);
    }
}
