package com.backend.boundedContext.payment.app;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.AdminTopupPgPaymentDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupPgPaymentDto.PgPayment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 충전 PG 결제 조회: 수동 확인 건을 정리하기 전에 PG 상태·취소 합계를 우리 기록과 비교해 보려고 그 자리에서 PG에 조회함
 * PG를 부르므로 트랜잭션 밖에서 실행함. 조회하지 못해도 우리 기록은 보여 주도록 PG 결과만 UNKNOWN으로 둠
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGetAdminTopupPgPaymentUseCase {

    private final TopupRepository topupRepository;
    private final TopupGateways topupGateways;

    public AdminTopupPgPaymentDto getPgPayment(Long topupId) {
        Topup topup = topupRepository.findById(topupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));
        return AdminTopupPgPaymentDto.of(topup, query(topup));
    }

    private PgPayment query(Topup topup) {
        if (topup.getPaymentKey() == null) {
            return PgPayment.notRequested();
        }
        try {
            return PgPayment.found(topupGateways.get(topup.getPgProvider()).getPayment(PgPaymentRef.from(topup)));
        } catch (PgPaymentNotFoundException e) {
            return PgPayment.notFound();
        } catch (PgResultUnknownException | PgConfigurationException e) {
            String cause = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            log.warn("관리자 PG 결제 조회 결과를 알 수 없습니다. topupId={}, pg={}, 원인={}", topup.getId(), topup.getPgProvider(), cause);
            return PgPayment.unknown(cause);
        }
    }
}
