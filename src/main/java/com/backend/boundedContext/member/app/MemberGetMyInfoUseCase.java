package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMyInfoUseCase {

    private final MemberRepository memberRepository;

    // 로그인 회원 정보 조회. 토큰은 유효하지만 회원이 없거나 탈퇴했으면 로그인 상태가 아니므로 401 UNAUTHORIZED
    public MyInfoDto getMyInfo(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new DomainException(GlobalErrorCode.UNAUTHORIZED));

        return MyInfoDto.from(member);
    }
}
