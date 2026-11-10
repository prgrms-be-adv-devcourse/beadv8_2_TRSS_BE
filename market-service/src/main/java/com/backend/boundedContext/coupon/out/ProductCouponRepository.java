package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.CouponStatus;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductCouponRepository extends JpaRepository<ProductCoupon, Long>, JpaSpecificationExecutor<ProductCoupon> {

    /** 쿠폰 ID로 상품 쿠폰 정보 조회 (쿠폰과 함께) */
    @Query("select pc from ProductCoupon pc join fetch pc.coupon where pc.coupon.id = :couponId")
    Optional<ProductCoupon> findWithCouponByCouponId(Long couponId);

    /** 쿠폰 코드로 조회 (쿠폰과 함께). 코드는 대문자로 바꿔서 넘긴다 */
    @Query("select pc from ProductCoupon pc join fetch pc.coupon where pc.code = :code")
    Optional<ProductCoupon> findWithCouponByCode(String code);

    /** 쿠폰들의 상품 쿠폰 정보 (관리자 목록, 상세). 장바구니 쿠폰 ID는 결과에 없다 */
    @Query("select pc from ProductCoupon pc where pc.coupon.id in :couponIds")
    List<ProductCoupon> findAllByCouponIdIn(Collection<Long> couponIds);

    boolean existsByCode(String code);

    /** 쿠폰의 협약 ID (쿠폰 엔티티를 읽지 않고). 수정할 때 협약부터 잠그려고 쓴다. 상품 쿠폰이 아니면 empty */
    @Query("select pc.partnershipId from ProductCoupon pc where pc.coupon.id = :couponId")
    Optional<Long> findPartnershipIdByCouponId(Long couponId);

    /**
     * 같은 인플루언서, 같은 상품으로 유효 기간이 겹치는 운영 중(중지되지 않고 아직 끝나지 않은) 쿠폰 수. excludeCouponId는 수정 중인 자기 자신
     * 기간은 시작 포함, 종료 미포함이라 [a, b)와 [c, d)는 a &lt; d 이고 c &lt; b일 때 겹친다
     * 이미 끝난 쿠폰(validUntil &lt;= now)은 빼서, 시작을 과거로 잡은 새 쿠폰이 끝난 쿠폰과 겹친다고 막히지 않게 한다
     */
    @Query("""
            select count(pc) from ProductCoupon pc join pc.coupon c
             where pc.influencerId = :influencerId
               and pc.productId = :productId
               and c.status = :active
               and c.validFrom < :validUntil
               and c.validUntil > :validFrom
               and c.validUntil > :now
               and c.id <> :excludeCouponId
            """)
    long countOverlapping(Long influencerId, Long productId, LocalDateTime validFrom, LocalDateTime validUntil,
                          Long excludeCouponId, CouponStatus active, LocalDateTime now);
}
