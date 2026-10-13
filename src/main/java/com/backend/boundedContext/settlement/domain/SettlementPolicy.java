package com.backend.boundedContext.settlement.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;

/**
 * 정산 업무 규칙과 정책 값
 *
 * 숫자는 application.yaml의 custom.settlement.*에서 주입, 규칙(계산식)은 이 클래스에만 둔다
 * 엔티티처럼 스프링 빈이 아닌 곳에서도 쓸 수 있도록 값은 static 필드로 두고 @Value setter로 채운다
 */
@Component
public class SettlementPolicy {

    /** 정산서 멱등 키 접두어
     * 결제 컨텍스트 원장 키는 여기에 -PAYOUT, -FEE를 붙인다 */
    public static final String IDEMPOTENCY_KEY_PREFIX = "SETTLEMENT-";

    /** 정산월 형식(yyyy-MM)
     *
     * 요청 검증에 사용 */
    public static final String MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

    /**
     * 재시도해도 결과가 같은 실패(ex "재시도 불가 사유")
     * 이 resultCode로 실패하면 바로 MANUAL_REQUIRED
     * 금액 불일치(정산서 합계 ≠ 후보 합계, 기존 지급 내역과 금액 다름), 판매자·지갑 없음
     * 결제 컨텍스트 코드는 PaymentApi.transfer의 "재시도해도 같은 실패" 목록과 같다
     */
    private static final Set<String> NON_RETRYABLE_CODES = Set.of(
            "409-SETTLEMENT_AMOUNT_MISMATCH",
            "404-SELLER_NOT_FOUND",
            "404-WALLET_NOT_FOUND",
            "404-HOLDING_NOT_FOUND",         // 결제: 주문의 HELD 홀딩 없음
            "409-HOLDING_INSUFFICIENT",      // 결제: 홀딩 남은 금액 < 이번 지급액
            "409-TRANSFER_AMOUNT_MISMATCH"); // 결제: 주문별 금액 합계 ≠ 지급액 + 수수료

    /** 수수료율(%) */
    public static int FEE_RATE;
    /** 매월 정산일 */
    public static int PAY_DAY_OF_MONTH;
    /** 자동 지급 최대 시도 횟수(최초 1회 + 재시도) */
    public static int MAX_ATTEMPTS;
    /** 재시도 간격(분)
     * 값 : 운영 120, 테스트 10 */
    public static int RETRY_INTERVAL_MINUTES;
    /** 월 정산 Job이 한 번에 처리하는 판매자·정산서 수 */
    public static int BATCH_CHUNK_SIZE;
    /** 재시도 스케줄러가 한 번에 처리하는 정산서 수 */
    public static int RETRY_BATCH_SIZE;

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

    @Value("${custom.settlement.batchChunkSize}")
    public void setBatchChunkSize(int batchChunkSize) {
        BATCH_CHUNK_SIZE = batchChunkSize;
    }

    @Value("${custom.settlement.retryBatchSize}")
    public void setRetryBatchSize(int retryBatchSize) {
        RETRY_BATCH_SIZE = retryBatchSize;
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
     * 주문 항목 금액 검증 : 0원보다 크고, 단가 × 수량과 같아야 정산 후보를 만든다.
     */
    public static boolean isValidLineAmount(long unitPrice, int quantity, long lineAmount) {
        if (lineAmount <= 0 || unitPrice <= 0 || quantity <= 0) return false;
        try {
            return Math.multiplyExact(unitPrice, quantity) == lineAmount;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /**
     * 정산월 = 구매 확정 시각(KST)이 속한 달, "yyyy-MM".
     */
    public static String toSettlementMonth(LocalDateTime confirmedAt) {
        return YearMonth.from(confirmedAt).toString();
    }

    /** 이미 끝난 달인지. 월 정산은 끝난 달만 실행할 수 있다 */
    public static boolean isClosedMonth(YearMonth targetMonth, LocalDateTime now) {
        return targetMonth.isBefore(YearMonth.from(now));
    }

    /**
     * 정산서 멱등 키 SETTLEMENT-{settlementId}. 한 번 정해지면 바꾸지 않는다.
     */
    public static String idempotencyKeyOf(Long settlementId) {
        return IDEMPOTENCY_KEY_PREFIX + settlementId;
    }

    /** 재시도해도 결과가 같은 실패인지 */
    public static boolean isNonRetryable(String resultCode) {
        return resultCode != null && NON_RETRYABLE_CODES.contains(resultCode);
    }
}