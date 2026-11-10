package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponStatus;
import com.backend.boundedContext.coupon.domain.CouponIssuerType;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

        return (root, query, cb) -> displayStatusPredicate(root, cb, displayStatus, now);
    }

    // 상품 쿠폰을 등록한 판매자 (없으면 조건 없음)
    public static Specification<Coupon> sellerEquals(Long sellerId) {
        if (sellerId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("issuerType"), CouponIssuerType.SELLER),
                cb.equal(root.get("issuerId"), sellerId));
    }

    // 이 인플루언서에게 발행된 상품 쿠폰 (없으면 조건 없음)
    public static Specification<Coupon> influencerEquals(Long influencerId) {
        if (influencerId == null) return Specification.unrestricted();
        return (root, query, cb) -> {
            Subquery<Long> couponIds = query.subquery(Long.class);
            Root<ProductCoupon> productCoupon = couponIds.from(ProductCoupon.class);
            couponIds.select(productCoupon.get("coupon").get("id"))
                    .where(cb.equal(productCoupon.get("influencerId"), influencerId));
            return root.get("id").in(couponIds);
        };
    }

    /**
     * 화면 상태 조건. 쿠폰 경로(Root 또는 Join)를 받아 상품 쿠폰 목록에서도 같은 규칙을 쓴다.
     * 시작 포함, 종료 미포함(valid_from &lt;= now &lt; valid_until)이다.
     */
    public static Predicate displayStatusPredicate(Path<Coupon> coupon, CriteriaBuilder cb,
                                                   CouponDisplayStatus displayStatus, LocalDateTime now) {
        return switch (displayStatus) {
            case STOPPED -> cb.equal(coupon.get("status"), CouponStatus.STOPPED);
            case SCHEDULED -> cb.and(
                    cb.equal(coupon.get("status"), CouponStatus.ACTIVE),
                    cb.greaterThan(coupon.<LocalDateTime>get("validFrom"), now));
            case ACTIVE -> cb.and(
                    cb.equal(coupon.get("status"), CouponStatus.ACTIVE),
                    cb.lessThanOrEqualTo(coupon.<LocalDateTime>get("validFrom"), now),
                    cb.greaterThan(coupon.<LocalDateTime>get("validUntil"), now));
            case ENDED -> cb.and(
                    cb.equal(coupon.get("status"), CouponStatus.ACTIVE),
                    cb.lessThanOrEqualTo(coupon.<LocalDateTime>get("validUntil"), now));
        };
    }
}
