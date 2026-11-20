package com.backend.boundedContext.member.app;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.member.event.MemberSignedUpEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberCreateSeedMembersUseCase {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisher eventPublisher;

    // 개발용 시드 회원 생성 (1 관리자, 2 판매자, 3 구매자 순서로 저장). 지갑은 가입과 같이 이벤트로 생성
    public void createSeedMembers(String password) {
        String encodedPassword = passwordEncoder.encode(password);

        List<Member> members = List.of(
                Member.admin("admin@palette.test", encodedPassword, "관리자"),
                Member.signUp("seller@palette.test", encodedPassword, "판매자"),   // 일반 회원으로 만들고, 판매자 정보와 승인(APPROVED)은 MemberCreateSeedSellerUseCase에서 만듦
                Member.signUp("buyer@palette.test", encodedPassword, "구매자")
        );

        for (Member member : members) {
            memberRepository.save(member);
            eventPublisher.publish(new MemberSignedUpEvent(member.getId()));
        }

        log.info("시드 회원 생성: {}", members.stream().map(m -> m.getId() + "=" + m.getRole()).toList());
    }
}
