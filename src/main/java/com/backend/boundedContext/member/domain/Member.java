package com.backend.boundedContext.member.domain;

import java.time.LocalDateTime;
import java.util.Locale;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.member.dto.MemberDto;
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
    private String password;  // BCrypt 해시

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

    // 이메일은 대소문자를 구분하지 않으므로 앞뒤 공백을 지우고 소문자로 맞춤 (가입·인증·로그인·중복 확인 모두 같은 값을 쓰도록)
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    public static Member signUp(String email, String encodedPassword, String name) {
        return new Member(email, encodedPassword, name, MemberRole.USER);
    }

    public static Member admin(String email, String encodedPassword, String name) {
        return new Member(email, encodedPassword, name, MemberRole.ADMIN);
    }

    // 개발용 시드 판매자 (실제 판매자 권한은 판매자 승인 시 부여)
    public static Member seller(String email, String encodedPassword, String name) {
        return new Member(email, encodedPassword, name, MemberRole.SELLER);
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    // 다른 컨텍스트에 넘길 DTO
    public MemberDto toDto() {
        return new MemberDto(getId(), email, name, role, status);
    }
}
