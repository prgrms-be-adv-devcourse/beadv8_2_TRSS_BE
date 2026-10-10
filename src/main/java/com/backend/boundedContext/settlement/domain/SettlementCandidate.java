package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(nullable = false)
    private Long orderId;

    /** 같은 확정 이벤트를 두 번 받아도 후보가 하나만 생기도록 유니크 */
    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerId;

    /** 판매 금액 = 주문 항목 금액(단가 × 수량) */
    @Column(nullable = false)
    private long salePrice;

    /** 생성 당시 수수료율. 정책이 바뀌어도 과거 후보는 이 값으로 계산된 금액을 유지한다 */
    @Column(nullable = false)
    private int feeRate;

    @Column(nullable = false)
    private long fee;

    @Column(nullable = false)
    private long payoutAmount;

    @Column(nullable = false)
    private LocalDateTime confirmedAt;

    @Column(nullable = false, columnDefinition = "CHAR(7)")
    private String settlementMonth;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private Settlement settlement;

    /** Outbox 도입 전이라 항상 null */
    private Long outboxEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementCandidateStatus status;

    @Column(length = 300)
    private String excludedReason;

    private SettlementCandidate(Long orderId, Long orderItemId, Long sellerId, long salePrice, int feeRate,
                                LocalDateTime confirmedAt) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.sellerId = sellerId;
        this.salePrice = salePrice;
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
        if (orderId == null || orderItemId == null || sellerId == null || confirmedAt == null) {
            throw new IllegalArgumentException("정산 후보 필수 값이 비어 있습니다.");
        }
        return new SettlementCandidate(orderId, orderItemId, sellerId, salePrice, feeRate, confirmedAt);
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
        this.excludedReason = reason;
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
