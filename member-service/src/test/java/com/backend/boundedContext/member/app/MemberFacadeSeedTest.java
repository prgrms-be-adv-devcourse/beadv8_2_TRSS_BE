package com.backend.boundedContext.member.app;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberFacadeSeedTest {

    @Mock
    private MemberSupport memberSupport;

    @Mock
    private MemberCreateSeedMembersUseCase memberCreateSeedMembersUseCase;

    @Mock
    private MemberCreateSeedLogisticsUseCase memberCreateSeedLogisticsUseCase;

    @InjectMocks
    private MemberFacade memberFacade;

    @Test
    @DisplayName("회원이 없고 비밀번호가 있으면 시드 회원을 만든다")
    void createSeedMembers_emptyDb() {
        given(memberSupport.countMembers()).willReturn(0L);

        memberFacade.createSeedMembers("seed-password");

        verify(memberCreateSeedMembersUseCase).createSeedMembers("seed-password");
    }

    @Test
    @DisplayName("회원이 이미 있으면 아무것도 하지 않는다 (멱등)")
    void createSeedMembers_alreadyExists() {
        given(memberSupport.countMembers()).willReturn(3L);

        memberFacade.createSeedMembers("seed-password");

        verify(memberCreateSeedMembersUseCase, never()).createSeedMembers(anyString());
    }

    @Test
    @DisplayName("시드 비밀번호가 없으면(운영 환경) 아무것도 하지 않는다")
    void createSeedMembers_noPassword() {
        memberFacade.createSeedMembers("");
        memberFacade.createSeedMembers(null);

        verify(memberSupport, never()).countMembers();
        verify(memberCreateSeedMembersUseCase, never()).createSeedMembers(anyString());
    }

    @Test
    @DisplayName("물류 담당자 시드: 다른 회원이 이미 있어도 회원 수와 상관없이 만든다")
    void createSeedLogistics_existingDb() {
        memberFacade.createSeedLogistics("logistics@palette.test", "seed-password");

        verify(memberCreateSeedLogisticsUseCase).createSeedLogistics("logistics@palette.test", "seed-password");
        verify(memberSupport, never()).countMembers();
    }

    @Test
    @DisplayName("물류 담당자 시드: 시드 비밀번호가 없으면(운영 환경) 아무것도 하지 않는다")
    void createSeedLogistics_noPassword() {
        memberFacade.createSeedLogistics("logistics@palette.test", "");
        memberFacade.createSeedLogistics("logistics@palette.test", null);

        verify(memberCreateSeedLogisticsUseCase, never()).createSeedLogistics(anyString(), anyString());
    }
}
