package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * 협약 목록 검색 조건. 보내지 않은 조건은 적용하지 않는다
 */
public final class PartnershipSpecifications {

    private PartnershipSpecifications() {
    }

    public static Specification<Partnership> sellerEquals(Long sellerId) {
        if (sellerId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("sellerId"), sellerId);
    }

    public static Specification<Partnership> influencerEquals(Long influencerId) {
        if (influencerId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("influencerId"), influencerId);
    }

    public static Specification<Partnership> statusEquals(PartnershipStatus status) {
        if (status == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
