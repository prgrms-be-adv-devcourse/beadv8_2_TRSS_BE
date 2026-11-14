package com.backend.boundedContext.settlement.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 MemberApi로 SettlementMemberPort를 구현함 (정산 모듈 분리 전까지 사용)
 */
@Component
@RequiredArgsConstructor
public class SettlementMemberApiAdapter implements SettlementMemberPort {

    private final MemberApi memberApi;

    @Override
    public SellerDto getSeller(Long sellerId) {
        return memberApi.getSeller(sellerId);
    }

    @Override
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        return memberApi.getSellers(sellerIds);
    }

    @Override
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return memberApi.findSellerByMemberId(memberId);
    }
}
