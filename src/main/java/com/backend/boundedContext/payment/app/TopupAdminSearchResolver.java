package com.backend.boundedContext.payment.app;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 결제 조회 충전 탭 검색창에 들어온 검색어를 해석
 * 결제 탭(PaymentAdminSearchResolver)과 같은 규칙에 충전번호 검색을 더함
 * - @ 포함 : 회원 이메일(정확히 일치) → 그 회원의 충전 내역
 * - TOPUP- 로 시작 : 충전번호(토스 주문번호) 앞부분 일치 → 그 번호로 시작하는 충전 건. 화면은 앞 13자만 보여 줌
 *   너무 짧으면 거의 모든 충전 건이 걸리므로 TOPUP- 뒤 4자리 이상(총 10자 이상)일 때만 찾고, 그보다 짧으면 결과 없음
 * - 그 외(주문번호 ORD-… 포함) : 결과 없음
 */
@Component
@RequiredArgsConstructor
public class TopupAdminSearchResolver {

    private static final int ORDER_ID_PREFIX_MIN_LENGTH = Topup.TOSS_ORDER_ID_PREFIX.length() + 4;  // TOPUP- + 4자리

    private final MemberApi memberApi;

    public AdminTopupSearch resolve(String keyword, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(PaymentErrorCode.PAYMENT_PERIOD_INVALID);
        }
        if (keyword == null || keyword.isBlank()) {
            return AdminTopupSearch.all(from, to);
        }

        String trimmed = keyword.trim();
        if (trimmed.contains("@")) {
            return memberApi.findMemberByEmail(trimmed)
                    .map(MemberDto::id)
                    .map(memberId -> AdminTopupSearch.ofMember(memberId, from, to))
                    .orElseGet(() -> AdminTopupSearch.none(from, to));
        }
        if (trimmed.startsWith(Topup.TOSS_ORDER_ID_PREFIX) && trimmed.length() >= ORDER_ID_PREFIX_MIN_LENGTH) {
            return AdminTopupSearch.ofOrderIdPrefix(trimmed, from, to);
        }
        return AdminTopupSearch.none(from, to);
    }
}
