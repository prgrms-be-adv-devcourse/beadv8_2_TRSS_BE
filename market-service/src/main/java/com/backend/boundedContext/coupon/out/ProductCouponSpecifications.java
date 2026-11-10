package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 쿠폰 목록 검색 조건 (판매자, 인플루언서 목록)
 */
public final class ProductCouponSpecifications {

    private ProductCouponSpecifications() {
    }

    /**
     * 보내지 않은 조건은 적용하지 않는다. 화면 상태는 {@link CouponSpecifications#displayStatusPredicate}와 같은 규칙이다.
     * 목록 조회에서는 쿠폰을 fetch join으로 함께 읽고, 개수 조회에서는 일반 join만 한다(개수 쿼리에는 fetch를 쓸 수 없다).
     */
    public static Specification<ProductCoupon> search(Long sellerId, Long influencerId, CouponDisplayStatus status, LocalDateTime now) {
        return (root, query, cb) -> {
            Join<ProductCoupon, Coupon> coupon = couponJoin(root, query);

            List<Predicate> predicates = new ArrayList<>();
            if (sellerId != null) {
                predicates.add(cb.equal(coupon.get("issuerId"), sellerId));   // 상품 쿠폰의 등록자는 항상 판매자
            }
            if (influencerId != null) {
                predicates.add(cb.equal(root.get("influencerId"), influencerId));
            }
            if (status != null) {
                predicates.add(CouponSpecifications.displayStatusPredicate(coupon, cb, status, now));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    @SuppressWarnings("unchecked")
    private static Join<ProductCoupon, Coupon> couponJoin(Root<ProductCoupon> root, CriteriaQuery<?> query) {
        Class<?> resultType = query.getResultType();
        if (resultType == Long.class || resultType == long.class) {
            return root.join("coupon");
        }
        return (Join<ProductCoupon, Coupon>) root.<ProductCoupon, Coupon>fetch("coupon");
    }
}
