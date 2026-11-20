package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartnershipRepository extends JpaRepository<Partnership, Long>, JpaSpecificationExecutor<Partnership> {

    /**
     * 협약을 쓰기 잠금해 조회한다. 수락, 거절, 종료와 쿠폰 발행이 같은 협약에서 겹치지 않게 한다
     * (종료 직후에 쿠폰이 발행되거나, 같은 인플루언서와 상품으로 기간이 겹치는 쿠폰이 동시에 발행되는 것을 막는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Partnership p where p.id = :id")
    Optional<Partnership> findByIdForUpdate(Long id);

    /** 같은 판매자와 인플루언서 사이에 이 상태들의 협약이 있는지 (요청 중이거나 진행 중인 협약 중복 확인) */
    boolean existsBySellerIdAndInfluencerIdAndStatusIn(Long sellerId, Long influencerId, Collection<PartnershipStatus> statuses);

    /** 판매자의 이 상태들의 협약을 ID 순으로 쓰기 잠금해 조회한다 (판매자 제재로 자동 종료) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Partnership p where p.sellerId = :sellerId and p.status in :statuses order by p.id")
    List<Partnership> findAllForUpdateBySellerIdAndStatusIn(Long sellerId, Collection<PartnershipStatus> statuses);
}
