package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.shared.member.dto.MemberDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberFacade {

    private final MemberCheckEmailUseCase memberCheckEmailUseCase;
    private final MemberSendEmailCodeUseCase memberSendEmailCodeUseCase;
    private final MemberVerifyEmailCodeUseCase memberVerifyEmailCodeUseCase;
    private final MemberSignUpUseCase memberSignUpUseCase;
    private final MemberLoginUseCase memberLoginUseCase;
    private final MemberRefreshTokenUseCase memberRefreshTokenUseCase;
    private final MemberLogoutUseCase memberLogoutUseCase;
    private final MemberCreateSeedMembersUseCase memberCreateSeedMembersUseCase;
    private final MemberGetMyInfoUseCase memberGetMyInfoUseCase;
    private final MemberSupport memberSupport;

    // 가입 가능한 이메일인지 확인
    @Transactional(readOnly = true)
    public void checkEmail(String email) {
        memberCheckEmailUseCase.checkEmail(email);
    }

    // 6자리 인증코드 발송
    public void sendEmailCode(String email) {
        memberSendEmailCodeUseCase.sendEmailCode(email);
    }

    // 인증 완료 토큰 반환
    public String verifyEmailCode(String email, String code) {
        return memberVerifyEmailCodeUseCase.verifyEmailCode(email, code);
    }

    // 회원과 예치금 지갑을 한 트랜잭션으로 생성하고 회원 ID 반환
    @Transactional
    public Long signUp(String email, String password, String name, String verificationToken) {
        return memberSignUpUseCase.signUp(email, password, name, verificationToken);
    }

    // 회원 조회 (다른 컨텍스트용)
    @Transactional(readOnly = true)
    public MemberDto getMember(Long memberId) {
        return memberSupport.getMember(memberId).toDto();
    }

    // 로그인 (Access·Refresh Token 발급)
    @Transactional(readOnly = true)
    public AuthTokens login(String email, String password) {
        return memberLoginUseCase.login(email, password);
    }

    // Access·Refresh Token 재발급 (Refresh Token 회전)
    @Transactional(readOnly = true)
    public AuthTokens refresh(String refreshToken) {
        return memberRefreshTokenUseCase.refresh(refreshToken);
    }

    // 로그아웃 (Refresh Token 폐기)
    public void logout(String refreshToken) {
        memberLogoutUseCase.logout(refreshToken);
    }

    // 개발용 시드 회원 생성 (비밀번호가 없거나 회원이 이미 있으면 아무것도 하지 않음)
    @Transactional
    public void createSeedMembers(String password) {
        if (password == null || password.isBlank()) {
            log.info("시드 회원 비밀번호(MEMBER_SEED_PASSWORD)가 없어 회원 시드를 건너뜀");
            return;
        }
        if (memberSupport.countMembers() > 0) return;

        memberCreateSeedMembersUseCase.createSeedMembers(password);
    }

    // 내 정보 조회
    @Transactional(readOnly = true)
    public MyInfoDto getMyInfo(Long memberId) {
        return memberGetMyInfoUseCase.getMyInfo(memberId);
    }
}
