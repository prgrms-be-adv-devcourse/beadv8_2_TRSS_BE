package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.event.MemberSignedUpEvent;

@ExtendWith(MockitoExtension.class)
class MemberCreateSeedMembersUseCaseTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private MemberCreateSeedMembersUseCase memberCreateSeedMembersUseCase;

    @Test
    @DisplayName("관리자·판매자·구매자 순서로 저장하고, 각 회원의 가입 완료 이벤트(지갑 생성)를 발행한다")
    void createSeedMembers() {
        AtomicLong ids = new AtomicLong(1);
        given(passwordEncoder.encode("seed-password")).willReturn("encoded");
        given(memberRepository.save(any(Member.class))).willAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            ReflectionTestUtils.setField(member, "id", ids.getAndIncrement());
            return member;
        });

        memberCreateSeedMembersUseCase.createSeedMembers("seed-password");

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Member::getRole)
                .containsExactly(MemberRole.ADMIN, MemberRole.USER, MemberRole.USER);
        assertThat(saved.getAllValues()).extracting(Member::getPassword).containsOnly("encoded");

        verify(eventPublisher).publish(new MemberSignedUpEvent(1L));
        verify(eventPublisher).publish(new MemberSignedUpEvent(2L));
        verify(eventPublisher).publish(new MemberSignedUpEvent(3L));
    }
}
