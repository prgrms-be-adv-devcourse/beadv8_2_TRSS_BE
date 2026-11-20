package com.backend.boundedContext.payment.domain;

import java.time.LocalDateTime;
import java.util.List;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.order.dto.ReturnReasonCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 반품 환불 기록 (주문당 1개). 반품 승인 이벤트를 받으면 REQUESTED로 먼저 남기고, 환불은 따로 처리한다.
// 환불이 실패해도 이 행과 사유가 남는다. 일시 오류는 Kafka 컨슈머 재시도로 다시 처리한다(재시도 횟수는 retryCount)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_refund")
public class PaymentRefund extends BaseIdAndTime {

    // 시스템 오류로 이만큼 실패하면 더 이상 자동으로 재시도하지 않고 사람이 확인한다
    public static final int MAX_SYSTEM_ERROR_RETRIES = 5;

    private static final int FAIL_REASON_MAX_LENGTH = 300;

    // 주문당 반품 승인은 한 번뿐이라 유니크. 이벤트 ID가 달라도 같은 주문은 한 번만 환불한다
    @Column(nullable = false, unique = true)
    private Long orderId;

    // 처리할 때 주문의 결제를 찾아 채운다. 결제를 못 찾으면 비어 있다
    private Long paymentId;

    // 이벤트의 환불 금액. 결제 금액과 같은지 대조하는 데만 쓴다
    @Column(nullable = false)
    private long requestedAmount;

    // 구매자가 실제로 받은 금액 (COMPLETED 때 채움). 단순 변심이면 상품값만(처음 배송비 제외)
    @Column(nullable = false)
    private long amount;

    // 구매자가 결제한 반품 회수 택배비 (단순 변심만, 결제할 때 채움. 나머지는 0)
    @Column(nullable = false)
    private long returnPickupFee;

    // 반품 사유. 사유를 싣기 전에 발행된 이벤트면 null이고 전액 환불한다
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReturnReasonCode reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentRefundStatus status;

    // 시스템 오류(FAILED_SYSTEM_ERROR)로 실패한 횟수
    @Column(nullable = false)
    private int retryCount;

    @Column(length = FAIL_REASON_MAX_LENGTH)
    private String failReason;

    private LocalDateTime refundedAt;

    public static PaymentRefund request(Long orderId, long requestedAmount, ReturnReasonCode reason) {
        PaymentRefund refund = new PaymentRefund();
        refund.orderId = orderId;
        refund.requestedAmount = requestedAmount;
        refund.reason = reason;
        refund.status = PaymentRefundStatus.REQUESTED;
        return refund;
    }

    // 지금 환불을 처리해도 되는 상태인지 확인: 처리 전이거나, 시스템 오류로 실패했지만 재시도 횟수가 남은 경우
    public boolean isProcessable() {
        return status == PaymentRefundStatus.REQUESTED ||
                (status == PaymentRefundStatus.FAILED_SYSTEM_ERROR && retryCount < MAX_SYSTEM_ERROR_RETRIES);
    }

    // 단순 변심이면 처음 배송비를 돌려주지 않고(상품값만 환불), 구매자가 반품 회수 택배비를 따로 결제해야 환불한다.
    // 기타·불량·사유 없음은 바로 전액 환불한다.
    public boolean isChangeOfMind() {
        return reason == ReturnReasonCode.CHANGE_OF_MIND;
    }

    // 단순 변심: 확인(결제·금액·정산 여부)은 끝났고, 구매자가 회수 택배비를 결제할 때까지 환불을 미룬다. 돈은 움직이지 않는다
    public void requirePickupFee(Long paymentId) {
        requireProcessable();
        if (!isChangeOfMind()) throw new IllegalStateException("단순 변심 반품만 회수 택배비를 받습니다: " + reason);
        this.paymentId = paymentId;
        this.status = PaymentRefundStatus.PICKUP_FEE_REQUIRED;
        this.failReason = null;
    }

    // 환불 완료. 처리할 수 있는 상태이거나, 단순 변심이면 회수 택배비를 결제한 뒤여야 한다
    public void complete(Long paymentId, long amount, LocalDateTime now) {
        if (!isProcessable() && !isPickupFeePaid()) {
            throw new IllegalStateException("환불을 완료할 수 없는 상태입니다: " + status + ", returnPickupFee=" + returnPickupFee);
        }
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = PaymentRefundStatus.COMPLETED;
        this.failReason = null;
        this.refundedAt = now;
    }

    // 단순 변심 반품 회수 택배비 결제: 구매자 예치금 → 플랫폼 지갑. 결제 대기일 때 한 번만 한다(이어서 환불을 완료할 수 있게 됨)
    // 구매자 잔액이 모자라면 Wallet이 402 INSUFFICIENT_BALANCE를 던진다(호출한 쪽이 먼저 확인해 충전 권장액을 함께 알린다)
    public List<WalletTransaction> payPickupFee(Wallet buyerWallet, Wallet platformWallet, long pickupFee) {
        if (status != PaymentRefundStatus.PICKUP_FEE_REQUIRED || returnPickupFee > 0) {
            throw new DomainException(PaymentErrorCode.REFUND_PICKUP_FEE_NOT_REQUIRED);
        }
        this.returnPickupFee = pickupFee;
        return List.of(
                buyerWallet.change(WalletTransactionType.RETURN_PICKUP_FEE_PAYMENT, pickupFee, getId(), pickupFeePaymentIdempotencyKey()),
                platformWallet.change(WalletTransactionType.RETURN_PICKUP_FEE, pickupFee, getId(), pickupFeeIdempotencyKey()));
    }

    // 결제 대기 중에 회수 택배비가 결제됐는지
    private boolean isPickupFeePaid() {
        return status == PaymentRefundStatus.PICKUP_FEE_REQUIRED && returnPickupFee > 0;
    }

    // 실패 기록. 시스템 오류면 재시도 횟수를 늘린다. paymentId는 결제를 찾은 경우에만 넘긴다(못 찾았으면 null)
    public void fail(PaymentRefundStatus failStatus, Long paymentId, String reason) {
        if (!failStatus.isFailure()) throw new IllegalArgumentException("실패 상태가 아닙니다: " + failStatus);
        requireProcessable();
        if (paymentId != null) this.paymentId = paymentId;
        this.status = failStatus;
        this.failReason = truncate(reason);
        if (failStatus == PaymentRefundStatus.FAILED_SYSTEM_ERROR) this.retryCount++;
    }

    // 환불 원장의 멱등 키. 환불 ID 기준이라 같은 환불은 재시도해도 원장이 한 번만 생긴다
    public String idempotencyKey() {
        return "REFUND-" + getId();
    }

    public String couponSubsidyIdempotencyKey() {
        return idempotencyKey() + "-COUPON_SUBSIDY";
    }

    public String shippingFeeIdempotencyKey() {
        return idempotencyKey() + "-SHIPPING_FEE";
    }

    // 회수 택배비: 플랫폼 입금(RETURN_PICKUP_FEE)·구매자 차감(RETURN_PICKUP_FEE_PAYMENT)
    public String pickupFeeIdempotencyKey() {
        return idempotencyKey() + "-RETURN_PICKUP_FEE";
    }

    public String pickupFeePaymentIdempotencyKey() {
        return idempotencyKey() + "-RETURN_PICKUP_FEE_PAYMENT";
    }

    private void requireProcessable() {
        if (!isProcessable()) throw new IllegalStateException("처리할 수 없는 환불 상태입니다: " + status + ", retryCount=" + retryCount);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= FAIL_REASON_MAX_LENGTH) return reason;
        return reason.substring(0, FAIL_REASON_MAX_LENGTH);
    }
}
