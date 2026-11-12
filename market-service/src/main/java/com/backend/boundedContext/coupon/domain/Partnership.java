package com.backend.boundedContext.coupon.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 판매자와 인플루언서의 협약. 진행 중(ACTIVE)인 협약이 있어야 상품 쿠폰을 발행할 수 있다.
 *
 * <p>같은 판매자와 인플루언서 사이에는 요청 중이거나 진행 중인 협약을 하나만 둔다.
 * 앱에서 먼저 확인하고, DB에서도 부분 유니크 인덱스(uk_partnership_open)로 한 번 더 막는다.
 * 거절되거나 종료된 뒤에는 다시 요청할 수 있다.
 */
@Entity
@Table(name = "partnership",
        indexes = {
                @Index(name = "idx_partnership_seller", columnList = "seller_id, id"),
                @Index(name = "idx_partnership_influencer", columnList = "influencer_id, id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Partnership extends BaseIdAndTime {

    // 판매자 ID(seller.id)
    @Column(nullable = false)
    private Long sellerId;

    // 인플루언서 ID(influencer.id, 회원 ID가 아님)
    @Column(nullable = false)
    private Long influencerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartnershipStatus status;

    // 인플루언서 수수료율(%), 15~30. 요청할 때 정하고 바꿀 수 없다 (바꾸려면 종료 뒤 다시 요청)
    @Column(nullable = false)
    private Integer commissionRate;

    @Column(length = 500)
    private String requestMessage;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime acceptedAt;

    private LocalDateTime rejectedAt;

    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PartnershipEndedBy endedBy;

    // 판매자가 인플루언서에게 협약 요청
    public static Partnership request(Long sellerId, Long influencerId, Integer commissionRate, String message, LocalDateTime now) {
        Partnership partnership = new Partnership();
        partnership.sellerId = sellerId;
        partnership.influencerId = influencerId;
        partnership.commissionRate = commissionRate;
        partnership.requestMessage = message;
        partnership.status = PartnershipStatus.REQUESTED;
        partnership.requestedAt = now;
        return partnership;
    }

    // 인플루언서 수락. 요청 중일 때만
    public void accept(LocalDateTime now) {
        checkStatus(PartnershipStatus.REQUESTED);
        this.status = PartnershipStatus.ACTIVE;
        this.acceptedAt = now;
    }

    // 인플루언서 거절. 요청 중일 때만
    public void reject(LocalDateTime now) {
        checkStatus(PartnershipStatus.REQUESTED);
        this.status = PartnershipStatus.REJECTED;
        this.rejectedAt = now;
    }

    // 판매자나 인플루언서가 종료. 진행 중일 때만 (요청 중인 협약은 인플루언서가 거절한다)
    public void end(PartnershipEndedBy endedBy, LocalDateTime now) {
        checkStatus(PartnershipStatus.ACTIVE);
        close(endedBy, now);
    }

    // 판매자 제재로 자동 종료. 요청 중인 협약도 함께 닫는다
    public void endBySystem(LocalDateTime now) {
        if (!isOpen()) {
            throw new DomainException(CouponErrorCode.PARTNERSHIP_STATE_INVALID);
        }
        close(PartnershipEndedBy.SYSTEM, now);
    }

    private void close(PartnershipEndedBy endedBy, LocalDateTime now) {
        this.status = PartnershipStatus.ENDED;
        this.endedAt = now;
        this.endedBy = endedBy;
    }

    private void checkStatus(PartnershipStatus expected) {
        if (status != expected) {
            throw new DomainException(CouponErrorCode.PARTNERSHIP_STATE_INVALID);
        }
    }

    public boolean isActive() {
        return status == PartnershipStatus.ACTIVE;
    }

    // 요청 중이거나 진행 중 (같은 판매자와 인플루언서 사이에 하나만 있을 수 있는 상태)
    public boolean isOpen() {
        return status == PartnershipStatus.REQUESTED || status == PartnershipStatus.ACTIVE;
    }

    public boolean isOwnedBySeller(Long sellerId) {
        return Objects.equals(this.sellerId, sellerId);
    }

    public boolean isForInfluencer(Long influencerId) {
        return Objects.equals(this.influencerId, influencerId);
    }
}
