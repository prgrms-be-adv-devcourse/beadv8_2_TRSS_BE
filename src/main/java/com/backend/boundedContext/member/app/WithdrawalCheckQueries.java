package com.backend.boundedContext.member.app;

import java.util.function.Supplier;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberPortUnavailableException;
import com.backend.global.exception.DomainException;

/**
 * 탈퇴·판매자 철회를 판단하려고 다른 모듈을 조회함 (회원 탈퇴 조건 조회·요청, 판매자 철회 요청·승인)
 * 조회하지 못하면 확인 없이 통과시키지 않고 503 WITHDRAWAL_CHECK_UNAVAILABLE로 거부함
 */
final class WithdrawalCheckQueries {

    private WithdrawalCheckQueries() {
    }

    static <T> T query(Supplier<T> portCall) {
        try {
            return portCall.get();
        } catch (MemberPortUnavailableException e) {
            throw new DomainException(MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE, e);
        }
    }
}
