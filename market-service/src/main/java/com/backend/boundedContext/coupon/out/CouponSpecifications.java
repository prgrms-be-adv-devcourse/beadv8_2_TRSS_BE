package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponStatus;
import com.backend.boundedContext.coupon.domain.CouponType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

/**
 * 쿠폰 목록 검색 조건
 *
 * <p>화면 상태는 저장하지 않으므로, {@link Coupon#displayStatus}와 같은 규칙을 SQL 조건으로 옮긴다.
 * 시작 포함, 종료 미포함(valid_from &lt;= now &lt; valid_until)이다.
 */
public final class CouponSpecifications {

    private CouponSpecifications() {
    }

    // 쿠폰 종류 (없으면 조건 없음)
    public static Specification<Coupon> typeEquals(CouponType type) {
        if (type == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("couponType"), type);
    }

    // 화면 상태 (없으면 조건 없음)
    public static Specification<Coupon> displayStatusEquals(CouponDisplayStatus displayStatus, LocalDateTime now) {
        if (displayStatus == null) return Specification.unrestricted();

        return (root, query, cb) -> switch (displayStatus) {
            case STOPPED -> cb.equal(root.get("status"), CouponStatus.STOPPED);
            case SCHEDULED -> cb.and(
                    cb.equal(root.get("status"), CouponStatus.ACTIVE),
                    cb.greaterThan(root.<LocalDateTime>get("validFrom"), now));
            case ACTIVE -> cb.and(
                    cb.equal(root.get("status"), CouponStatus.ACTIVE),
                    cb.lessThanOrEqualTo(root.<LocalDateTime>get("validFrom"), now),
                    cb.greaterThan(root.<LocalDateTime>get("validUntil"), now));
            case ENDED -> cb.and(
                    cb.equal(root.get("status"), CouponStatus.ACTIVE),
                    cb.lessThanOrEqualTo(root.<LocalDateTime>get("validUntil"), now));
        };
    }
}
