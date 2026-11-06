package com.backend.boundedContext.member.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원에게 연결된 소셜 계정. 이메일로 가입한 회원이 로그인 수단으로 연결 (소셜 계정으로 가입하지는 않음)
 * 소셜 계정 하나는 한 회원에게만, 회원은 제공자별로 하나만 연결
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member_social_account",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_member_social_account_provider_user", columnNames = {"provider", "provider_user_id"}),
                @UniqueConstraint(name = "uk_member_social_account_member_provider", columnNames = {"member_id", "provider"})
        })
public class MemberSocialAccount extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    // 제공자가 준 회원 식별자 (카카오 회원번호). 다시 연결해도 바뀌지 않음
    @Column(nullable = false, length = 100)
    private String providerUserId;

    private MemberSocialAccount(Member member, SocialProvider provider, String providerUserId) {
        this.member = member;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    public static MemberSocialAccount link(Member member, SocialProvider provider, String providerUserId) {
        return new MemberSocialAccount(member, provider, providerUserId);
    }
}
