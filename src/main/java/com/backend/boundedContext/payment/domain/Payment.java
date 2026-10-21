package com.backend.boundedContext.payment.domain;

import java.time.LocalDateTime;

import com.backend.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문 결제 시도 1건. 한 주문에 시도가 여러 건 생길 수 있고, PAID는 주문당 1건 (홀딩의 order_id 유니크로 보장)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id, created_at"),
        @Index(name = "idx_payment_order_number", columnList = "order_number")
})
public class Payment extends BaseIdAndTime {

    @Column(nullable = false)
    private Long orderId;

    // 주문번호 (관리자 결제 조회 표시·검색용). 주문번호는 바뀌지 않아 결제 시점 값을 그대로 둔다
    @Column(nullable = false, length = 40)
    private String orderNumber;

    // 구매자 회원 ID
    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentStatus status;

    @Column(length = 300)
    private String failReason;

    // 주문 컨텍스트가 넘긴 멱등 키. 같은 키로 다시 호출하면 이 결제 건의 결과를 그대로 반환
    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    private LocalDateTime paidAt;

    private LocalDateTime refundedAt;

    // 결제 성공 건 (지갑 차감·홀딩 생성은 호출한 쪽에서 같은 트랜잭션으로 처리)
    public static Payment paid(Long orderId, String orderNumber, Wallet wallet, long amount, String idempotencyKey,
                               LocalDateTime paidAt) {
        Payment payment = create(
                orderId,
                orderNumber,
                wallet,
                amount,
                idempotencyKey,
                PaymentStatus.PAID
        );
        payment.paidAt = paidAt;
        return payment;
    }

    // 결제 실패 건 (FAILED_*). 결제 트랜잭션과 별도 트랜잭션으로 저장
    public static Payment failed(Long orderId, String orderNumber, Wallet wallet, long amount, String idempotencyKey,
                                 PaymentStatus failStatus, String failReason) {
        if (!failStatus.isFailure()) throw new IllegalArgumentException("실패 상태가 아닙니다: " + failStatus);
        Payment payment = create(
                orderId,
                orderNumber,
                wallet,
                amount,
                idempotencyKey,
                failStatus
        );
        payment.failReason = failReason;
        return payment;
    }

    private static Payment create(Long orderId, String orderNumber, Wallet wallet, long amount, String idempotencyKey,
                                  PaymentStatus status) {
        if (amount <= 0) throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다: " + amount);
        if (orderNumber == null || orderNumber.isBlank()) throw new IllegalArgumentException("주문번호가 없습니다: orderId=" + orderId);
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.orderNumber = orderNumber;
        payment.memberId = wallet.getMemberId();
        payment.wallet = wallet;
        payment.amount = amount;
        payment.idempotencyKey = idempotencyKey;
        payment.status = status;
        return payment;
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    // 결제 원장(PAYMENT)의 멱등 키
    public String paymentIdempotencyKey() {
        return "PAYMENT-" + getId();
    }
}
