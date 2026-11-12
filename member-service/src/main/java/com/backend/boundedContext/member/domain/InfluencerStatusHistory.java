package com.backend.boundedContext.member.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
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
@Table(name = "influencer_status_history",
        indexes = @Index(name = "idx_influencer_status_history_influencer", columnList = "influencer_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerStatusHistory extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id", nullable = false)
    private Influencer influencer;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private InfluencerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InfluencerStatus toStatus;

    private String reason;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    public static InfluencerStatusHistory applied(Influencer influencer) {
        return record(influencer, null, null);
    }

    static InfluencerStatusHistory record(Influencer influencer, InfluencerStatus fromStatus, String reason) {
        InfluencerStatusHistory history = new InfluencerStatusHistory();
        history.influencer = influencer;
        history.fromStatus = fromStatus;
        history.toStatus = influencer.getStatus();
        history.reason = reason;
        history.changedAt = LocalDateTime.now();
        return history;
    }
}
