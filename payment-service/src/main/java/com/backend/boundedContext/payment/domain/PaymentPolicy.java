package com.backend.boundedContext.payment.domain;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.backend.global.exception.DomainException;

@Component
public class PaymentPolicy {

    private final long topupUnit;
    private final long topupMax;
    private final Duration topupReadyTtl;
    private final Duration topupReconcileDelay;
    private final int topupReconcileBatchSize;
    private final Duration topupStuckWarnAfter;
    private final Long adminMemberId;

    public PaymentPolicy(
            @Value("${custom.payment.topupUnit}") long topupUnit,
            @Value("${custom.payment.topupMax}") long topupMax,
            @Value("${custom.payment.topupReadyExpireMinutes}") long topupReadyExpireMinutes,
            @Value("${custom.payment.topupReconcileDelayMinutes}") long topupReconcileDelayMinutes,
            @Value("${custom.payment.topupReconcileBatchSize}") int topupReconcileBatchSize,
            @Value("${custom.payment.topupStuckWarnMinutes}") long topupStuckWarnMinutes,
            @Value("${custom.payment.adminMemberId}") Long adminMemberId
    ) {
        this.topupUnit = topupUnit;
        this.topupMax = topupMax;
        this.topupReadyTtl = Duration.ofMinutes(topupReadyExpireMinutes);
        this.topupReconcileDelay = Duration.ofMinutes(topupReconcileDelayMinutes);
        this.topupReconcileBatchSize = topupReconcileBatchSize;
        this.topupStuckWarnAfter = Duration.ofMinutes(topupStuckWarnMinutes);
        this.adminMemberId = adminMemberId;
    }

    // 충전 금액: 충전 단위의 배수이면서 충전 단위 이상, 최대 금액 이하
    public void validateTopupAmount(long amount) {
        if (amount < topupUnit || amount > topupMax || amount % topupUnit != 0) {
            throw new DomainException(PaymentErrorCode.TOPUP_AMOUNT_INVALID);
        }
    }

    // 잔액 부족 시 충전 권장액: 부족액을 충전 단위로 올린 금액 (예: 18,000 → 20,000, 20,000 → 20,000)
    // 정수 나눗셈만으로 올림: (부족액 + 단위 - 1) / 단위 * 단위
    public long recommendedChargeAmount(long shortage) {
        if (shortage <= 0) return 0;
        return (shortage + topupUnit - 1) / topupUnit * topupUnit;
    }

    // READY 충전 건이 만료되기까지의 시간
    public Duration topupReadyTtl() {
        return topupReadyTtl;
    }

    // PROCESSING 충전 건을 토스에 다시 확인하기까지 기다리는 시간 (승인 요청이 아직 진행 중인 건과 겹치지 않게)
    public Duration topupReconcileDelay() {
        return topupReconcileDelay;
    }

    // 결과 확인 스케줄러가 한 번에 조회하는 충전 건수 (다 채우면 같은 실행에서 이어서 조회)
    public int topupReconcileBatchSize() {
        return topupReconcileBatchSize;
    }

    // 결과가 정해지지 않은 상태(PROCESSING·CANCELING·METHOD_CANCELING)로 이 시간이 넘게 남은 충전 건은 오류 로그로 알림
    public Duration topupStuckWarnAfter() {
        return topupStuckWarnAfter;
    }

    // 정산 수수료(FEE)를 받는 관리자 회원 ID
    public Long adminMemberId() {
        return adminMemberId;
    }
}
