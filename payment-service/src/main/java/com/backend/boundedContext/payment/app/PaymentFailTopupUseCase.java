package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.backend.boundedContext.payment.app.dto.TopupFailDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 충전 실패 기록: PG 결제창에서 실패·취소로 돌아온 충전 건을 실패로 기록
 * 결제창 단계의 실패라 승인 API가 호출되지 않으므로, 프론트가 실패 리다이렉트를 받으면 이 API로 알려 줌
 */
@Component
@RequiredArgsConstructor
public class PaymentFailTopupUseCase {

    private static final String DEFAULT_REASON = "결제가 완료되지 않았습니다.";

    private final TopupRepository topupRepository;
    private final TopupGateways topupGateways;

    /**
     * 1. 충전 건 잠금 조회 (조건에 memberId 포함, 남의 충전 건이면 404)
     * 2. READY가 아니면 409 TOPUP_ALREADY_PROCESSED (승인 중·완료된 건을 결제창 실패로 덮어쓰지 않음)
     * 3. 충전 건의 PG가 사용자 취소 코드라고 하면 FAILED_USER_CANCEL, 그 외 → FAILED_PG_DECLINED. 실패 사유는 PG 메시지
     */
    public TopupFailDto failTopup(Long memberId, Long topupId, String code, String message) {
        Topup topup = topupRepository.findByIdAndMemberIdForUpdate(topupId, memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));

        if (topup.getStatus() != TopupStatus.READY) {
            throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        }

        boolean userCancel = topupGateways.get(topup.getPgProvider()).isUserCancel(code);
        TopupStatus failStatus = userCancel ? TopupStatus.FAILED_USER_CANCEL : TopupStatus.FAILED_PG_DECLINED;
        topup.fail(failStatus, StringUtils.hasText(message) ? message : DEFAULT_REASON);

        return TopupFailDto.from(topup);
    }
}
