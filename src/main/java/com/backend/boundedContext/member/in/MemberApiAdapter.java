package com.backend.boundedContext.member.in;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.shared.member.dto.MemberDto;
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
}
