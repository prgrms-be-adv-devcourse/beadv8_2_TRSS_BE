package com.backend.boundedContext.payment.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 MemberApi로 PaymentMemberPort를 구현함 (결제 서비스 분리 전까지 사용)
 * custom.payment.memberClient가 없거나 local이면 이 어댑터를 씀
 */
@Component
@ConditionalOnProperty(name = "custom.payment.memberClient", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class PaymentMemberApiAdapter implements PaymentMemberPort {

    private final MemberApi memberApi;

    @Override
    public void checkActiveMember(Long memberId) {
        memberApi.checkActiveMember(memberId);
    }

    @Override
    public List<MemberDto> getMembers(Collection<Long> memberIds) {
        return memberApi.getMembers(memberIds);
    }

    @Override
    public Optional<MemberDto> findMemberByEmail(String email) {
        return memberApi.findMemberByEmail(email);
    }
}
