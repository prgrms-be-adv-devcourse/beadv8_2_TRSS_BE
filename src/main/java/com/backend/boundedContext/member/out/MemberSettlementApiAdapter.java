package com.backend.boundedContext.member.out;

import org.springframework.stereotype.Component;

import com.backend.shared.settlement.out.SettlementApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 SettlementApi로 MemberSettlementPort를 구현함 (정산 모듈 분리 전까지 사용)
 */
@Component
@RequiredArgsConstructor
public class MemberSettlementApiAdapter implements MemberSettlementPort {

    private final SettlementApi settlementApi;

    @Override
    public long getUnsettledAmount(Long sellerId) {
        return settlementApi.getUnsettledAmount(sellerId);
    }

    @Override
    public void holdSeller(Long sellerId, String reason) {
        settlementApi.holdSeller(sellerId, reason);
    }
}
