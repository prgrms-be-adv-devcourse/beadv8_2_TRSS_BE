package com.backend.boundedContext.settlement.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;

// 정산 업무 규칙과 정책 값
@Component
public class SettlementPolicy {

    /** 정산서 멱등 키 접두어
     * 결제 컨텍스트 원장 키는 여기에 -PAYOUT, -FEE를 붙인다 */
    public static final String IDEMPOTENCY_KEY_PREFIX = "SETTLEMENT-";

    /** 수수료율(%) */
    public static int FEE_RATE;
    /** 매월 정산일 */
    public static int PAY_DAY_OF_MONTH;
    /** 자동 지급 최대 시도 횟수(최초 1회 + 재시도) */
    public static int MAX_ATTEMPTS;
    /** 재시도 간격(분) */
    public static int RETRY_INTERVAL_MINUTES;

    @Value("${custom.settlement.feeRate}")
    public void setFeeRate(int feeRate) {
        FEE_RATE = feeRate;
    }

    @Value("${custom.settlement.payDayOfMonth}")
    public void setPayDayOfMonth(int payDayOfMonth) {
        PAY_DAY_OF_MONTH = payDayOfMonth;
    }

    @Value("${custom.settlement.maxAttempts}")
    public void setMaxAttempts(int maxAttempts) {
        MAX_ATTEMPTS = maxAttempts;
    }

    @Value("${custom.settlement.retryIntervalMinutes}")
    public void setRetryIntervalMinutes(int retryIntervalMinutes) {
        RETRY_INTERVAL_MINUTES = retryIntervalMinutes;
    }

    /**
     * 수수료 = 판매가 × 수수료율 ÷ 100. 곱셈을 먼저 하고 long 나눗셈으로 소수점 아래를 버린다(품목별).
     */
    public static long calculateFee(long salePrice, int feeRate) {
        if (salePrice < 0) throw new IllegalArgumentException("판매가는 0 이상이어야 합니다: " + salePrice);
        if (feeRate < 0 || feeRate > 100) throw new IllegalArgumentException("수수료율은 0~100이어야 합니다: " + feeRate);
        return salePrice * feeRate / 100;
    }

    /**
     * 정산월 = 구매 확정 시각(KST)이 속한 달, "yyyy-MM".
     */
    public static String toSettlementMonth(LocalDateTime confirmedAt) {
        return YearMonth.from(confirmedAt).toString();
    }

    /**
     * 정산서 멱등 키 SETTLEMENT-{settlementId}. 한 번 정해지면 바꾸지 않는다.
     */
    public static String idempotencyKeyOf(Long settlementId) {
        return IDEMPOTENCY_KEY_PREFIX + settlementId;
    }
}
