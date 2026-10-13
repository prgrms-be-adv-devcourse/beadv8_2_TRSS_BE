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
    private final Long adminMemberId;

    public PaymentPolicy(
            @Value("${custom.payment.topupUnit}") long topupUnit,
            @Value("${custom.payment.topupMax}") long topupMax,
            @Value("${custom.payment.topupReadyExpireMinutes}") long topupReadyExpireMinutes,
            @Value("${custom.payment.adminMemberId}") Long adminMemberId
    ) {
        this.topupUnit = topupUnit;
        this.topupMax = topupMax;
        this.topupReadyTtl = Duration.ofMinutes(topupReadyExpireMinutes);
        this.adminMemberId = adminMemberId;
    }

    // 충전 금액: 충전 단위의 배수이면서 충전 단위 이상, 최대 금액 이하
    public void validateTopupAmount(long amount) {
        if (amount < topupUnit || amount > topupMax || amount % topupUnit != 0) {
            throw new DomainException(PaymentErrorCode.TOPUP_AMOUNT_INVALID);
        }
    }

    // READY 충전 건이 만료되기까지의 시간
    public Duration topupReadyTtl() {
        return topupReadyTtl;
    }

    // 정산 수수료(FEE)를 받는 관리자 회원 ID
    public Long adminMemberId() {
        return adminMemberId;
    }
}
