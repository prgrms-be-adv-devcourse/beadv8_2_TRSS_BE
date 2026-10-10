package com.backend.boundedContext.member.domain;

import java.time.LocalDateTime;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member")
public class Member extends BaseIdAndTime {

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;    // BCrypt 해시

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    private LocalDateTime deletedAt;    // 탈퇴 일시

    private Member(String email, String encodedPassword, String name, MemberRole role) {
        this.email = email;
        this.password = encodedPassword;
        this.name = name;
        this.role = role;
        this.status = MemberStatus.ACTIVE;
    }

    public static Member signUp(String email, String encodedPassword, String name) {
        return new Member(email, encodedPassword, name, MemberRole.USER);
    }

    public static Member admin(String email, String encodedPassword, String name) {
        return new Member(email, encodedPassword, name, MemberRole.ADMIN);
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }
}
