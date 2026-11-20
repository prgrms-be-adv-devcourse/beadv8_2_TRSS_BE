package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.shared.member.dto.MemberRole;

@ExtendWith(MockitoExtension.class)
class MemberCreateSeedLogisticsUseCaseTest {

    private static final String EMAIL = "logistics@palette.test";

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberCreateSeedLogisticsUseCase memberCreateSeedLogisticsUseCase;

    @Test
    @DisplayName("같은 이메일 회원이 없으면 물류 담당자(LOGISTICS) 계정을 만든다")
    void createSeedLogistics() {
        given(memberRepository.existsByEmail(EMAIL)).willReturn(false);
        given(passwordEncoder.encode("seed-password")).willReturn("encoded");
        given(memberRepository.save(any(Member.class))).willAnswer(invocation -> invocation.getArgument(0));

        memberCreateSeedLogisticsUseCase.createSeedLogistics(EMAIL, "seed-password");

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded");
        assertThat(saved.getValue().getName()).isEqualTo("물류 담당자");
        assertThat(saved.getValue().getRole()).isEqualTo(MemberRole.LOGISTICS);
    }

    @Test
    @DisplayName("같은 이메일 회원이 이미 있으면 아무것도 하지 않는다 (멱등)")
    void createSeedLogistics_alreadyExists() {
        given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

        memberCreateSeedLogisticsUseCase.createSeedLogistics(EMAIL, "seed-password");

        verify(memberRepository, never()).save(any(Member.class));
    }
}
