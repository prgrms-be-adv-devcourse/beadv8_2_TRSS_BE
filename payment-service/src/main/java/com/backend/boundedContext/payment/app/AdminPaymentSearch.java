package com.backend.boundedContext.payment.app;

import static com.backend.boundedContext.payment.out.AdminPaymentSpecifications.createdFrom;
import static com.backend.boundedContext.payment.out.AdminPaymentSpecifications.createdTo;
import static com.backend.boundedContext.payment.out.AdminPaymentSpecifications.memberEquals;
import static com.backend.boundedContext.payment.out.AdminPaymentSpecifications.orderNumberEquals;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.domain.Payment;

/**
 * 관리자 결제 조회의 검색어·기간을 해석한 결과
 * 검색어에 맞는 대상이 없으면 noResult (조회하지 않고 빈 페이지)
 */
public record AdminPaymentSearch(
        Long memberId,       // 이메일로 찾은 회원 ID (검색어가 이메일일 때만)
        String orderNumber,  // 검색어가 주문번호일 때만
        boolean noResult,    // 검색어에 맞는 결제가 있을 수 없음
        LocalDate from,
        LocalDate to
) {
    // 검색어 없음: 기간만 적용
    public static AdminPaymentSearch all(LocalDate from, LocalDate to) {
        return new AdminPaymentSearch(null, null, false, from, to);
    }

    public static AdminPaymentSearch ofMember(Long memberId, LocalDate from, LocalDate to) {
        return new AdminPaymentSearch(memberId, null, false, from, to);
    }

    public static AdminPaymentSearch ofOrderNumber(String orderNumber, LocalDate from, LocalDate to) {
        return new AdminPaymentSearch(null, orderNumber, false, from, to);
    }

    // 검색어(이메일)에 맞는 회원이 없음
    public static AdminPaymentSearch none(LocalDate from, LocalDate to) {
        return new AdminPaymentSearch(null, null, true, from, to);
    }

    public Specification<Payment> paymentSpec() {
        return Specification.allOf(memberEquals(memberId), orderNumberEquals(orderNumber), createdFrom(from), createdTo(to));
    }
}
