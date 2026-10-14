package com.backend.boundedContext.settlement.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;

/**
 * 정산 컨텍스트 정책 값과 업무 규칙
 *
 * 값은 인스턴스, 규칙은 정적
 *   값(application.yaml의 custom.settlement.* 6개): 생성자에서 @Value로 받아 final 인스턴스 필드에 둔다.
 *       필요한 UseCase·배치·스케줄러가 이 빈을 생성자로 주입받아 getFeeRate()처럼 꺼내 쓴다.
 *   규칙(calculateFee·isValidLineAmount·isNonRetryable 등): 정책 값에 기대지 않는 순수 계산식이라 정적 메서드로 둔다.
 *       필요한 값(수수료율 등)은 인자로 받으므로 엔티티·테스트 어디서든 스프링 없이 부를 수 있다.
 *   상수(MONTH_PATTERN 등): @Pattern 같은 애너테이션 값에 쓰려면 컴파일 상수여야 하므로 static final로 둔다.
 *
 * 같은 클래스에 SettlementPolicy.calculateFee(...)(정적)와 settlementPolicy.getFeeRate()(인스턴스)가 함께 있으니,
 * 값이 필요하면 주입받은 빈에서, 계산이 필요하면 클래스 이름으로 부른다.
 *
 * 10/14 코드 리뷰 반영
 * 값을 생성자 주입 final 필드로 옮김
 * 스프링 밖에서는 new SettlementPolicy(...)로 값을 직접 넣어야만 만들 수 있으므로 0이 몰래 들어가지 않는다.
 * @Value는 형변환만 하고 범위는 검사하지 않으므로 생성자에서 범위를 직접 검사한다.
 * 범위를 벗어나면 빈 생성이 실패해 앱이 뜨지 않는다(잘못된 수수료율로 지급이 나가는 것보다 기동 실패가 되도록 수정함)
 */
@Component
@Getter
public class SettlementPolicy {

    // ===== 상수(정적) =====

    /** 정산서 멱등 키 접두어. 결제 컨텍스트 원장 키는 여기에 -PAYOUT, -FEE를 붙인다 */
    public static final String IDEMPOTENCY_KEY_PREFIX = "SETTLEMENT-";

    /** 정산월 형식(yyyy-MM). 요청 검증(@Pattern)에 쓰므로 컴파일 상수로 둔다 */
    public static final String MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

    /**
     * 재시도해도 결과가 같은 실패("재시도 불가 사유"). 이 resultCode로 실패하면 바로 MANUAL_REQUIRED.
     * 금액 불일치(정산서 합계 ≠ 후보 합계, 기존 지급 내역과 금액 다름), 판매자·지갑 없음.
     * 결제 컨텍스트 코드는 PaymentApi.transfer 주석(PR #51)의 "재시도해도 같은 실패" 목록과 같다.
     */
    private static final Set<String> NON_RETRYABLE_CODES = Set.of(
            "409-SETTLEMENT_AMOUNT_MISMATCH",
            "404-SELLER_NOT_FOUND",
            "404-WALLET_NOT_FOUND",
            "404-HOLDING_NOT_FOUND",         // 결제: 주문의 HELD 홀딩 없음
            "409-HOLDING_INSUFFICIENT",      // 결제: 홀딩 남은 금액 < 이번 지급액
            "409-TRANSFER_AMOUNT_MISMATCH"); // 결제: 주문별 금액 합계 ≠ 지급액 + 수수료

    // ===== 값(인스턴스) — 10/14 리뷰 반영: 정적 필드 + setter에서 생성자 주입 final 필드로 변경 =====

    // 수수료율(%). 0~100
    private final int feeRate;

    // 매월 정산일. 1~28 (29~31일은 없는 달이 있어 막는다)
    private final int payDayOfMonth;

    // 자동 지급 최대 시도 횟수(최초 1회 + 재시도). 1 이상
    private final int maxAttempts;

    // 재시도 간격(분). 정책 v2: 운영 120, 테스트 10. 1 이상
    private final int retryIntervalMinutes;

    // 월 정산 Job이 한 번에 처리하는 판매자·정산서 수. 1 이상
    private final int batchChunkSize;

    // 재시도 스케줄러가 한 번에 처리하는 정산서 수. 1 이상
    private final int retryBatchSize;

    /**
     * 스프링은 application.yaml 값을 넣어 한 번 만들고, 단위 테스트는 new SettlementPolicy(10, 10, 3, 10, 100, 50)처럼 직접 만든다.
     * 인자 순서: feeRate, payDayOfMonth, maxAttempts, retryIntervalMinutes, batchChunkSize, retryBatchSize
     */
    public SettlementPolicy(
            @Value("${custom.settlement.feeRate}") int feeRate,
            @Value("${custom.settlement.payDayOfMonth}") int payDayOfMonth,
            @Value("${custom.settlement.maxAttempts}") int maxAttempts,
            @Value("${custom.settlement.retryIntervalMinutes}") int retryIntervalMinutes,
            @Value("${custom.settlement.batchChunkSize}") int batchChunkSize,
            @Value("${custom.settlement.retryBatchSize}") int retryBatchSize
    ) {
        // 10/14 리뷰 반영: @ConfigurationProperties + @Validated(@Min·@Max)가 하던 범위 검사를 생성자에서 직접 한다
        this.feeRate = requireRange("custom.settlement.feeRate", feeRate, 0, 100);
        this.payDayOfMonth = requireRange("custom.settlement.payDayOfMonth", payDayOfMonth, 1, 28);
        this.maxAttempts = requirePositive("custom.settlement.maxAttempts", maxAttempts);
        this.retryIntervalMinutes = requirePositive("custom.settlement.retryIntervalMinutes", retryIntervalMinutes);
        this.batchChunkSize = requirePositive("custom.settlement.batchChunkSize", batchChunkSize);
        this.retryBatchSize = requirePositive("custom.settlement.retryBatchSize", retryBatchSize);
    }

    /** 범위를 벗어나면 어떤 설정 키가 잘못됐는지 담아 예외를 던진다(빈 생성 실패 → 앱 기동 실패) */
    private static int requireRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("%s는 %d~%d 사이여야 합니다: %d".formatted(key, min, max, value));
        }
        return value;
    }

    private static int requirePositive(String key, int value) {
        return requireRange(key, value, 1, Integer.MAX_VALUE);
    }

    // ===== 규칙(정적) — 정책 값에 기대지 않으므로 스프링 없이 어디서든 부른다 =====

    /**
     * 수수료 = 판매가 × 수수료율 ÷ 100. 곱셈을 먼저 하고 long 나눗셈으로 소수점 아래를 버린다(품목별).
     * 수수료율은 정적 필드가 아니라 인자로 받는다(호출하는 쪽이 settlementPolicy.getFeeRate()를 넘긴다).
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
     * Settlement 엔티티가 정적으로 부르므로 인스턴스 메서드로 바꾸지 않는다.
     */
    public static String idempotencyKeyOf(Long settlementId) {
        return IDEMPOTENCY_KEY_PREFIX + settlementId;
    }

    /** 재시도해도 결과가 같은 실패인지 */
    public static boolean isNonRetryable(String resultCode) {
        return resultCode != null && NON_RETRYABLE_CODES.contains(resultCode);
    }
}