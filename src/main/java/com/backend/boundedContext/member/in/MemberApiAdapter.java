package com.backend.boundedContext.member.in;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberApiAdapter implements MemberApi {

    @Lazy  // 컨텍스트 간 순환 참조 방지
    private final MemberFacade memberFacade;

    @Override
    public MemberDto getMember(Long memberId) {
        return memberFacade.getMember(memberId);
    }

    @Override
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return memberFacade.findSellerByMemberId(memberId);
    }

    @Override
    public SellerDto getSeller(Long sellerId) {
        return memberFacade.getSeller(sellerId);
    }

    @Override
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        return memberFacade.getSellers(sellerIds);
    }
}
