package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 정산 후보
 * 구매 확정된 주문 항목 1개 = 후보 1개이며, 판매자에게 줄 돈(지급액)과 플랫폼 수수료를 함께 담는다.
 * 다른 컨텍스트(주문·회원)는 ID만 저장하고, 같은 컨텍스트의 정산서만 연관관계로 참조한다.
 */
@Entity
@Table(name = "settlement_candidate",
        uniqueConstraints = @UniqueConstraint(name = "uk_candidate_order_item", columnNames = "order_item_id"),
        indexes = {
                @Index(name = "idx_candidate_settlement", columnList = "settlement_id"),
                @Index(name = "idx_candidate_seller_status", columnList = "seller_id, status, settlement_month")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementCandidate extends BaseIdAndTime {

    // 원장 무결성 1단계: 만들 때 정해진 식별값·금액은 UPDATE 문에서 빠진다(updatable = false). 상태만 바뀐다
    @Column(nullable = false, updatable = false)
    private Long orderId;

    /** 같은 확정 이벤트를 두 번 받아도 후보가 하나만 생기도록 유니크 */
    @Column(nullable = false, updatable = false)
    private Long orderItemId;

    @Column(nullable = false, updatable = false)
    private Long sellerId;

    /**
     * 판매 금액 = 주문 항목 금액(단가 × 수량) - 상품 쿠폰 할인액
     * 상품 쿠폰은 판매자 부담이라 판매가에서 뺀다. 장바구니 쿠폰은 플랫폼 부담(결제 지원금)이라 빼지 않는다
     */
    @Column(nullable = false, updatable = false)
    private long salePrice;

    /** 판매가에서 뺀 상품 쿠폰 할인액(판매자 부담, 없으면 0). 할인 전 금액 = salePrice + productCouponDiscount */
    @Column(nullable = false, updatable = false)
    private long productCouponDiscount;

    /** 주문할 때의 상품명 스냅샷(정산 내역·엑셀 표시용). 이름이 실리기 전 구매 확정 이벤트로 만든 후보는 null */
    @Column(length = 100, updatable = false)
    private String productName;

    /** 주문할 때의 옵션명 스냅샷. productName과 같이 이전 후보는 null */
    @Column(length = 50, updatable = false)
    private String optionName;

    /** 생성 당시 수수료율. 정책이 바뀌어도 과거 후보는 이 값으로 계산된 금액을 유지한다 */
    @Column(nullable = false, updatable = false)
    private int feeRate;

    @Column(nullable = false, updatable = false)
    private long fee;

    @Column(nullable = false, updatable = false)
    private long payoutAmount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime confirmedAt;

    @JdbcTypeCode(SqlTypes.CHAR)   // CHAR(7) 컬럼과 맞춤 (없으면 ddl-auto validate가 VARCHAR를 기대해 실패)
    @Column(nullable = false, updatable = false, columnDefinition = "CHAR(7)")
    private String settlementMonth;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private Settlement settlement;

    /** 예전 설계에서 남은 컬럼. 쓰지 않는다(항상 null). 원본 이벤트는 sourceEventId에 남긴다 */
    private Long outboxEventId;

    /** 이 후보를 만든 구매 확정 이벤트의 ID(봉투 eventId). 어떤 이벤트에서 왔는지 이력을 따라가는 데 쓴다. 이전 후보는 null */
    @Column(updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementCandidateStatus status;

    @Column(length = 300)
    private String excludedReason;

    private SettlementCandidate(Long orderId, Long orderItemId, Long sellerId, long salePrice,
                                long productCouponDiscount, String productName, String optionName, int feeRate,
                                LocalDateTime confirmedAt) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.sellerId = sellerId;
        this.salePrice = salePrice;
        this.productCouponDiscount = productCouponDiscount;
        this.productName = productName;
        this.optionName = optionName;
        this.feeRate = feeRate;
        this.fee = SettlementPolicy.calculateFee(salePrice, feeRate);
        this.payoutAmount = salePrice - this.fee;
        this.confirmedAt = confirmedAt;
        this.settlementMonth = SettlementPolicy.toSettlementMonth(confirmedAt);
        this.status = SettlementCandidateStatus.READY;
    }

    /**
     * 구매 확정된 주문 항목으로 후보를 만든다. 수수료는 품목별로 버림, 지급액은 판매가에서 수수료를 뺀 값이다.
     */
    public static SettlementCandidate create(Long orderId, Long orderItemId, Long sellerId, long salePrice,
                                             int feeRate, LocalDateTime confirmedAt) {
        return create(orderId, orderItemId, sellerId, salePrice, 0L, feeRate, confirmedAt);
    }

    /**
     * 상품 쿠폰 할인이 있는 후보(이름 없음). salePrice는 할인 뒤 금액, productCouponDiscount는 뺀 할인액(기록용)
     */
    public static SettlementCandidate create(Long orderId, Long orderItemId, Long sellerId, long salePrice,
                                             long productCouponDiscount, int feeRate, LocalDateTime confirmedAt) {
        return create(orderId, orderItemId, sellerId, salePrice, productCouponDiscount, null, null, feeRate,
                confirmedAt);
    }

    /**
     * 구매 확정 항목으로 만드는 후보. salePrice는 할인 뒤 금액, productCouponDiscount는 뺀 할인액(기록용),
     * productName·optionName은 주문할 때의 이름 스냅샷(없으면 null)
     */
    public static SettlementCandidate create(Long orderId, Long orderItemId, Long sellerId, long salePrice,
                                             long productCouponDiscount, String productName, String optionName,
                                             int feeRate, LocalDateTime confirmedAt) {
        if (orderId == null || orderItemId == null || sellerId == null || confirmedAt == null) {
            throw new IllegalArgumentException("정산 후보 필수 값이 비어 있습니다.");
        }
        if (productCouponDiscount < 0) {
            throw new IllegalArgumentException("상품 쿠폰 할인액은 0 이상이어야 합니다.");
        }
        return new SettlementCandidate(orderId, orderItemId, sellerId, salePrice, productCouponDiscount, productName,
                optionName, feeRate, confirmedAt);
    }

    /** 이 후보를 만든 구매 확정 이벤트 ID를 남긴다. 저장 전에 한 번만 정한다 */
    public void recordSourceEvent(UUID sourceEventId) {
        if (this.sourceEventId == null) this.sourceEventId = sourceEventId;
    }

    /** 정산서에 묶는다. READY 후보만 가능하다 */
    void include(Settlement settlement) {
        requireReady();
        this.settlement = settlement;
        this.status = SettlementCandidateStatus.INCLUDED;
    }

    /** 관리자가 정산 대상에서 제외한다. 아직 정산서에 묶이지 않은 READY 후보만 가능하다 */
    public void exclude(String reason) {
        requireReady();
        this.status = SettlementCandidateStatus.EXCLUDED;
        this.excludedReason = reason != null && reason.length() > 300 ? reason.substring(0, 300) : reason;
    }

    /**
     * 금액 스냅샷이 서로 맞는지(판별 단계 CandidateAmountIntegrityRule)
     * 판매가 > 0, 수수료 = 판매가 × 수수료율 ÷ 100(버림), 지급액 = 판매가 − 수수료
     */
    public boolean hasConsistentAmounts() {
        if (salePrice <= 0 || feeRate < 0 || feeRate > 100) return false;
        return fee == SettlementPolicy.calculateFee(salePrice, feeRate) && payoutAmount == salePrice - fee;
    }

    /** 원장 무결성 1단계: 정산 후보는 지우지 않는다(정정은 조정 후보 추가로) */
    @PreRemove
    private void denyRemove() {
        throw new IllegalStateException("정산 후보는 삭제할 수 없습니다. 제외(EXCLUDED)나 조정으로 정정하세요.");
    }

    /** 연관된 정산서 ID. 지연 로딩 프록시여도 ID만 꺼내므로 추가 조회가 없다 */
    public Long getSettlementId() {
        return settlement == null ? null : settlement.getId();
    }

    private void requireReady() {
        if (status != SettlementCandidateStatus.READY) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_CANDIDATE_STATE_INVALID);
        }
    }
}
