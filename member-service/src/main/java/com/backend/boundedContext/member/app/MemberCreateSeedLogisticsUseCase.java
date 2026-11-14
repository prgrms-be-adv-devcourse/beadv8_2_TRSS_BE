package com.backend.boundedContext.member.app;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberCreateSeedLogisticsUseCase {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 개발용 시드 물류 담당자 생성: 물류 담당자 계정(LOGISTICS)을 만든다
     * 물류 계정은 구매·판매를 하지 않아 지갑이 필요 없으므로 MemberSignedUpEvent를 발행하지 않는다
     * 같은 이메일의 회원이 이미 있으면 아무것도 하지 않는다(멱등)
     */
    public void createSeedLogistics(String email, String password) {
        if (memberRepository.existsByEmail(email)) return;

        Member member = memberRepository.save(Member.logistics(email, passwordEncoder.encode(password), "물류 담당자"));

        log.info("시드 물류 담당자 생성: memberId={}", member.getId());
    }
}
