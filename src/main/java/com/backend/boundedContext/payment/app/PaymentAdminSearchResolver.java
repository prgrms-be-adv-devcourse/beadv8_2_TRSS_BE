package com.backend.boundedContext.payment.app;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 결제 조회 검색창에 들어온 검색어를 해석 (정확히 일치)
 * - @ 포함 : 회원 이메일 → 그 회원의 결제
 * - 그 외(주문번호 ORD-… 포함) : 결과 없음. TODO 주문번호 검색은 OrderApi에 주문번호 조회가 생기면 추가
 */
@Component
@RequiredArgsConstructor
public class PaymentAdminSearchResolver {

    private final MemberApi memberApi;

    public AdminPaymentSearch resolve(String keyword, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(PaymentErrorCode.PAYMENT_PERIOD_INVALID);
        }

        if (keyword == null || keyword.isBlank()) {
            return AdminPaymentSearch.all(from, to);
        }

        String trimmed = keyword.trim();
        if (trimmed.contains("@")) {
            return memberApi.findMemberByEmail(trimmed)
                    .map(MemberDto::id)
                    .map(memberId -> AdminPaymentSearch.ofMember(memberId, from, to))
                    .orElseGet(() -> AdminPaymentSearch.none(from, to));
        }
        return AdminPaymentSearch.none(from, to);
    }
}
