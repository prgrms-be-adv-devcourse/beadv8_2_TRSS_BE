package com.backend.boundedContext.member.app;

import java.util.function.Supplier;

import com.backend.boundedContext.member.out.MemberPortUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * 화면에 보여 주기만 하는 값을 다른 모듈에서 조회함 (내 판매자 정보의 미지급 정산 금액 등)
 * 조회하지 못하면 그 값만 null로 비우고 나머지는 그대로 응답함. 판단이 걸린 조회는 WithdrawalCheckQueries를 씀
 */
@Slf4j
final class DisplayPortQueries {

    private DisplayPortQueries() {
    }

    static <T> T orNull(Supplier<T> portCall) {
        try {
            return portCall.get();
        } catch (MemberPortUnavailableException e) {
            log.warn("다른 모듈 조회에 실패해 값을 비우고 응답함: {}", e.getMessage());
            return null;
        }
    }
}
