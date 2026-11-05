package com.backend.boundedContext.member.out;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberType;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerStatus;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

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

    // 회원 유형: 판매자는 역할이 아니라 판매자 상태(APPROVED)로 판단한다
    public static Specification<Member> memberTypeEquals(MemberType memberType) {
        if (memberType == null) return Specification.unrestricted();
        return switch (memberType) {
            case ADMIN -> (root, query, cb) -> cb.equal(root.get("role"), MemberRole.ADMIN);
            case SELLER -> (root, query, cb) -> cb.and(isUser(root, cb), cb.exists(approvedSeller(root, query, cb)));
            case USER -> (root, query, cb) -> cb.and(isUser(root, cb), cb.not(cb.exists(approvedSeller(root, query, cb))));
        };
    }

    private static Predicate isUser(Root<Member> root, CriteriaBuilder cb) {
        return cb.equal(root.get("role"), MemberRole.USER);
    }

    // 이 회원의 판매 중(APPROVED) 판매자 정보
    private static Subquery<Long> approvedSeller(Root<Member> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<Seller> seller = subquery.from(Seller.class);
        return subquery.select(seller.get("id"))
                .where(cb.equal(seller.get("member"), root), cb.equal(seller.get("status"), SellerStatus.APPROVED));
    }
}
