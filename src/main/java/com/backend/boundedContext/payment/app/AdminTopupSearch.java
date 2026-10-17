package com.backend.boundedContext.payment.app;

import static com.backend.boundedContext.payment.out.AdminTopupSpecifications.createdFrom;
import static com.backend.boundedContext.payment.out.AdminTopupSpecifications.createdTo;
import static com.backend.boundedContext.payment.out.AdminTopupSpecifications.memberEquals;
import static com.backend.boundedContext.payment.out.AdminTopupSpecifications.orderIdEquals;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.domain.Topup;

/**
 * 관리자 결제 조회 충전 탭의 검색어·기간을 해석한 결과
 * 검색어에 맞는 대상이 없으면 noResult (조회하지 않고 빈 페이지)
 */
public record AdminTopupSearch(
        Long memberId,     // 이메일로 찾은 회원 ID (검색어가 이메일일 때만)
        String orderId,    // 충전번호 (검색어가 TOPUP-… 일 때만)
        boolean noResult,  // 검색어에 맞는 충전 건이 있을 수 없음
        LocalDate from,
        LocalDate to
) {
    // 검색어 없음: 기간만 적용
    public static AdminTopupSearch all(LocalDate from, LocalDate to) {
        return new AdminTopupSearch(null, null, false, from, to);
    }

    public static AdminTopupSearch ofMember(Long memberId, LocalDate from, LocalDate to) {
        return new AdminTopupSearch(memberId, null, false, from, to);
    }

    public static AdminTopupSearch ofOrderId(String orderId, LocalDate from, LocalDate to) {
        return new AdminTopupSearch(null, orderId, false, from, to);
    }

    // 검색어에 맞는 회원이 없거나 지원하지 않는 검색어
    public static AdminTopupSearch none(LocalDate from, LocalDate to) {
        return new AdminTopupSearch(null, null, true, from, to);
    }

    public Specification<Topup> topupSpec() {
        return Specification.allOf(memberEquals(memberId), orderIdEquals(orderId), createdFrom(from), createdTo(to));
    }
}
