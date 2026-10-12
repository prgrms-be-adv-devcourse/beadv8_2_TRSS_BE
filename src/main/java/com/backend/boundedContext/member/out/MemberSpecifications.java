package com.backend.boundedContext.member.out;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

// 관리자 회원 목록 검색 조건 (값이 없으면 조건을 걸지 않음: Specification.unrestricted())
public final class MemberSpecifications {

    private MemberSpecifications() {
    }

    // 이메일 부분 일치 (대소문자 무시)
    public static Specification<Member> emailContains(String email) {
        if (email == null || email.isBlank()) return Specification.unrestricted();
        String pattern = "%" + email.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("email")), pattern);
    }

    public static Specification<Member> statusEquals(MemberStatus status) {
        if (status == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Member> roleEquals(MemberRole role) {
        if (role == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }
}
