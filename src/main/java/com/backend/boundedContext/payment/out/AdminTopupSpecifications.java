package com.backend.boundedContext.payment.out;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.domain.Topup;

// 관리자 결제 조회 충전 탭 검색 조건. 조건이 없으면(null) 걸지 않음
public final class AdminTopupSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private AdminTopupSpecifications() {
    }

    public static Specification<Topup> memberEquals(Long memberId) {
        if (memberId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("memberId"), memberId);
    }

    // 충전번호(토스 주문번호) 앞부분 일치. 입력값의 %·_는 와일드카드가 아닌 글자 그대로 찾음
    // toss_order_id는 정렬 규칙이 C라 like '앞부분%'도 유니크 인덱스로 찾음
    public static Specification<Topup> orderIdStartsWith(String orderIdPrefix) {
        if (orderIdPrefix == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.like(root.get("tossOrderId"), escapeLike(orderIdPrefix) + "%", LIKE_ESCAPE);
    }

    // 충전 준비일이 from 이후 (from 당일 포함)
    public static Specification<Topup> createdFrom(LocalDate from) {
        if (from == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    // 충전 준비일이 to 이전 (to 당일 포함)
    public static Specification<Topup> createdTo(LocalDate to) {
        if (to == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
