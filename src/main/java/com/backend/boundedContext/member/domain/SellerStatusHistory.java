package com.backend.boundedContext.member.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.member.dto.SellerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "seller_status_history",
        indexes = @Index(name = "idx_seller_status_history_seller", columnList = "seller_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerStatusHistory extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    // 처음 신청하면 null
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SellerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SellerStatus toStatus;

    // 반려·철회 요청·철회 거절·제재 사유
    private String reason;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    // 신청서 스냅샷. 반려·본인 철회(재신청으로 seller 행이 덮어써질 수 있는 상태)로 바뀐 줄에만 남긴다
    @Column(length = 100)
    private String businessName;

    @Column(length = 20)
    private String businessNumber;

    @Column(length = 50)
    private String representativeName;

    // 계좌번호는 암호화 대상이라 끝 4자리만
    @Column(length = 4)
    private String accountLast4;

    // 처음 신청한 줄
    public static SellerStatusHistory applied(Seller seller) {
        return of(seller, null, null);
    }

    // seller의 지금 상태(바뀐 뒤)를 toStatus로 기록한다
    static SellerStatusHistory of(Seller seller, SellerStatus fromStatus, String reason) {
        SellerStatusHistory history = new SellerStatusHistory();
        history.seller = seller;
        history.fromStatus = fromStatus;
        history.toStatus = seller.getStatus();
        history.reason = reason;
        history.changedAt = LocalDateTime.now();
        return history;
    }

    // 회원 탈퇴: 신청서 스냅샷의 개인정보(대표자명·계좌 끝 4자리)를 지운다
    public void anonymize() {
        this.representativeName = null;
        this.accountLast4 = null;
    }

    static SellerStatusHistory withSnapshot(Seller seller, SellerStatus fromStatus, String reason) {
        SellerStatusHistory history = of(seller, fromStatus, reason);
        history.businessName = seller.getBusinessName();
        history.businessNumber = seller.getBusinessNumber();
        history.representativeName = seller.getRepresentativeName();
        history.accountLast4 = seller.getAccountLast4();
        return history;
    }
}
