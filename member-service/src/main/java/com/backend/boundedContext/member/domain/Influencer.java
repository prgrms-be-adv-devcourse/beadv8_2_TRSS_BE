package com.backend.boundedContext.member.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "influencer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Influencer extends BaseIdAndTime {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Column(nullable = false, length = 30)
    private String activityName;

    @Column(nullable = false, length = 500)
    private String snsUrl;

    @Column(nullable = false, length = 500)
    private String introduction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InfluencerStatus status;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    // 신청이 승인/반려/해제된 시각
    private LocalDateTime decidedAt;

    @Version
    private Long version;

    public static Influencer apply(Member member, String activityName, String snsUrl, String introduction) {
        Influencer influencer = new Influencer();
        influencer.member = member;
        influencer.submit(activityName, snsUrl, introduction);
        return influencer;
    }

    // 반려 뒤 재신청
    public InfluencerStatusHistory reapply(String activityName, String snsUrl, String introduction) {
        if (status == InfluencerStatus.REVOKED) throw new DomainException(MemberErrorCode.INFLUENCER_REAPPLY_NOT_ALLOWED);
        if (status != InfluencerStatus.REJECTED) throw new DomainException(MemberErrorCode.INFLUENCER_ALREADY_APPLIED);

        InfluencerStatus from = status;
        submit(activityName, snsUrl, introduction);
        return InfluencerStatusHistory.record(this, from, null);
    }

    public InfluencerStatusHistory approve() {
        return changeStatus(InfluencerStatus.PENDING, InfluencerStatus.APPROVED, null);
    }

    public InfluencerStatusHistory reject(String reason) {
        return changeStatus(InfluencerStatus.PENDING, InfluencerStatus.REJECTED, reason);
    }

    public InfluencerStatusHistory revoke(String reason) {
        return changeStatus(InfluencerStatus.APPROVED, InfluencerStatus.REVOKED, reason);
    }

    public boolean isApproved() {
        return status == InfluencerStatus.APPROVED;
    }
    private void submit(String activityName, String snsUrl, String introduction) {
        this.activityName = activityName.strip();
        this.snsUrl = snsUrl.strip();
        this.introduction = introduction.strip();
        this.status = InfluencerStatus.PENDING;
        this.appliedAt = LocalDateTime.now();
        this.decidedAt = null;
    }

    private InfluencerStatusHistory changeStatus(InfluencerStatus from, InfluencerStatus to, String reason) {
        if (status != from) throw new DomainException(MemberErrorCode.INFLUENCER_STATE_INVALID);

        status = to;
        decidedAt = LocalDateTime.now();
        return InfluencerStatusHistory.record(this, from, reason);
    }
}
