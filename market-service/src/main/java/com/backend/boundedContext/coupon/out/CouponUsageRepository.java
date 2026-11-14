package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {

    /** 이 회원이 이 쿠폰을 이미 예약했거나 사용했는지 (1인 1회) */
    boolean existsByCouponIdAndMemberIdAndStatusIn(Long couponId, Long memberId, Collection<CouponUsageStatus> statuses);

    /** 이 주문에 이 종류의 쿠폰을 이미 예약했거나 사용했는지 (주문당 종류별 1개) */
    boolean existsByOrderIdAndCouponTypeAndStatusIn(Long orderId, CouponType couponType,
                                                    Collection<CouponUsageStatus> statuses);

    /** 쿠폰들 중 이 회원이 예약했거나 사용한 쿠폰 ID */
    @Query("""
            select distinct u.coupon.id from CouponUsage u
             where u.memberId = :memberId
               and u.coupon.id in :couponIds
               and u.status in :statuses
            """)
    List<Long> findCouponIdsByMember(Long memberId, Collection<Long> couponIds, Collection<CouponUsageStatus> statuses);

    /** 주문의 사용 기록 중 이 상태인 것 (쿠폰과 함께) */
    @Query("""
            select u from CouponUsage u join fetch u.coupon
             where u.orderId = :orderId
               and u.status = :status
             order by u.id
            """)
    List<CouponUsage> findAllWithCouponByOrderIdAndStatus(Long orderId, CouponUsageStatus status);

    /**
     * 결제 시작 확인: 주문의 예약(RESERVED) 기록 중 쿠폰을 지금 쓸 수 없는 것의 수 (중지됐거나 유효 기간 밖)
     * 쿠폰 조건은 Coupon.isUsableAt의 반대: ACTIVE가 아니거나 now < validFrom 이거나 validUntil <= now
     */
    @Query("""
            select count(u)
              from CouponUsage u
             where u.orderId = :orderId
               and u.status = com.backend.boundedContext.coupon.domain.CouponUsageStatus.RESERVED
               and (u.coupon.status <> com.backend.boundedContext.coupon.domain.CouponStatus.ACTIVE
                    or u.coupon.validFrom > :now
                    or u.coupon.validUntil <= :now)
            """)
    long countReservedWithUnusableCoupon(Long orderId, LocalDateTime now);

    /**
     * 결제 성공 반영: 주문의 예약(RESERVED) 기록을 모두 USED로 바꾼다. 바뀐 행 수를 돌려준다
     * 쿠폰 상태·기간은 보지 않는다(결제 시작 때 확인했고, 예약이 할인을 보장한다)
     * 기록 상태를 조건에 넣어, 해제(취소·만료)가 먼저 커밋했으면 그 행은 바꾸지 않는다(나중에 커밋한 쪽이 덮어쓰지 않음)
     * 벌크 UPDATE라 수정 시각 자동 기록(@LastModifiedDate)이 돌지 않으므로 updatedAt도 직접 넣는다
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CouponUsage u
               set u.status = com.backend.boundedContext.coupon.domain.CouponUsageStatus.USED,
                   u.usedAt = :now,
                   u.updatedAt = :now
             where u.orderId = :orderId
               and u.status = com.backend.boundedContext.coupon.domain.CouponUsageStatus.RESERVED
            """)
    int markUsedIfReserved(Long orderId, LocalDateTime now);

    /**
     * 예약 해제: 이 기록이 아직 예약(RESERVED)일 때만 RELEASED로 바꾼다. 바꿨으면 1, 이미 처리됐으면 0
     * 동시에 들어온 취소·만료·결제 성공 중 먼저 바꾼 한 쪽만 1을 받으므로, 1일 때만 남은 수량을 되돌린다(한 예약에 수량 한 번)
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CouponUsage u
               set u.status = com.backend.boundedContext.coupon.domain.CouponUsageStatus.RELEASED,
                   u.releasedAt = :now,
                   u.updatedAt = :now
             where u.id = :id
               and u.status = com.backend.boundedContext.coupon.domain.CouponUsageStatus.RESERVED
            """)
    int markReleasedIfReserved(Long id, LocalDateTime now);

    /** 쿠폰별 이 상태의 사용 수와 할인 합계 (관리자 목록, 상세) */
    @Query("""
            select new com.backend.boundedContext.coupon.out.CouponUsageStat(u.coupon.id, count(u), sum(u.discountAmount))
              from CouponUsage u
             where u.coupon.id in :couponIds
               and u.status = :status
             group by u.coupon.id
            """)
    List<CouponUsageStat> sumByCouponIdsAndStatus(Collection<Long> couponIds, CouponUsageStatus status);

    /** 쿠폰의 사용 기록 (관리자 상세) */
    Page<CouponUsage> findAllByCouponId(Long couponId, Pageable pageable);

    /** 회원의 사용 기록 (쿠폰과 함께, 내 쿠폰 사용 내역) */
    @EntityGraph(attributePaths = "coupon")
    Page<CouponUsage> findAllByMemberId(Long memberId, Pageable pageable);
}
