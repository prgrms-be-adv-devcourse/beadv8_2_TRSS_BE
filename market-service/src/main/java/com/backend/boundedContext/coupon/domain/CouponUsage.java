package com.backend.boundedContext.coupon.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 회원별 쿠폰 사용 기록
 *
 * <p>한 쿠폰을 여러 회원이 쓰므로 "썼는지"는 쿠폰이 아니라 이 기록으로 관리한다.
 * 1인 1회는 같은 쿠폰과 회원의 RESERVED, USED 기록이 있는지로 보고,
 * DB에서도 부분 유니크 인덱스(uk_coupon_usage_member_active)로 한 번 더 막는다.
 * 한 주문에는 쿠폰 종류(상품, 장바구니)마다 1개만 쓸 수 있다(최대 2개). 같은 주문·종류의 RESERVED, USED 기록이 있는지로 보고,
 * DB에서도 부분 유니크 인덱스(uk_coupon_usage_order_type)로 막는다. 그래서 쿠폰 종류를 이 기록에도 둔다.
 */
@Entity
@Table(name = "coupon_usage",
        indexes = {
                @Index(name = "idx_coupon_usage_member", columnList = "member_id, id"),
                @Index(name = "idx_coupon_usage_order", columnList = "order_id"),
                @Index(name = "idx_coupon_usage_coupon", columnList = "coupon_id, status")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponUsage extends BaseIdAndTime {

    // 1인 1회 판단에 들어가는 상태 (해제된 기록은 다시 쓸 수 있음)
    public static final List<CouponUsageStatus> OCCUPIED_STATUSES =
            List.of(CouponUsageStatus.RESERVED, CouponUsageStatus.USED);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long orderId;

    // 쿠폰 종류 (주문당 종류별 1개 판단용, 쿠폰에서 복사. 바뀌지 않는다)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private CouponType couponType;

    // 상품 쿠폰이 붙은 주문 항목. 장바구니 쿠폰은 null
    private Long orderItemId;

    @Column(nullable = false)
    private long discountAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponUsageStatus status;

    @Column(nullable = false)
    private LocalDateTime reservedAt;

    private LocalDateTime usedAt;

    private LocalDateTime releasedAt;

    // 주문 생성 때 예약
    public static CouponUsage reserve(Coupon coupon, Long memberId, Long orderId, Long orderItemId,
                                      long discountAmount, LocalDateTime now) {
        CouponUsage usage = new CouponUsage();
        usage.coupon = coupon;
        usage.memberId = memberId;
        usage.orderId = orderId;
        usage.couponType = coupon.getCouponType();
        usage.orderItemId = orderItemId;
        usage.discountAmount = discountAmount;
        usage.status = CouponUsageStatus.RESERVED;
        usage.reservedAt = now;
        return usage;
    }

    // 결제 성공 → 사용 처리
    public void use(LocalDateTime now) {
        validateReserved();
        this.status = CouponUsageStatus.USED;
        this.usedAt = now;
    }

    // 결제 전 취소나 만료 → 예약 해제
    public void release(LocalDateTime now) {
        validateReserved();
        this.status = CouponUsageStatus.RELEASED;
        this.releasedAt = now;
    }

    // 조회 조건에서 RESERVED만 고르므로 여기로 다른 상태가 오면 코드 오류
    private void validateReserved() {
        if (status != CouponUsageStatus.RESERVED) {
            throw new IllegalStateException("예약 상태의 쿠폰 사용 기록만 처리할 수 있습니다. usageId=" + getId() + ", status=" + status);
        }
    }
}
