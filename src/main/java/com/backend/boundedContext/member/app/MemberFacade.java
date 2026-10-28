package com.backend.boundedContext.member.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberType;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 컨텍스트의 회원·인증 기능. 판매자 기능은 {@link MemberSellerFacade}
 */
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
    private final MemberGetMembersUseCase memberGetMembersUseCase;
    private final MemberGetMemberDetailUseCase memberGetMemberDetailUseCase;
    private final MemberCreateSeedMembersUseCase memberCreateSeedMembersUseCase;
    private final MemberGetMyInfoUseCase memberGetMyInfoUseCase;
    private final MemberGetWithdrawalCheckUseCase memberGetWithdrawalCheckUseCase;
    private final MemberWithdrawUseCase memberWithdrawUseCase;
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

    // 로그인 상태인 회원인지 확인 (다른 컨텍스트용). 없거나 탈퇴했으면 401 UNAUTHORIZED
    @Transactional(readOnly = true)
    public void checkActiveMember(Long memberId) {
        memberSupport.getLoginMember(memberId);
    }

    // 여러 회원 조회 (다른 컨텍스트용). 없는 ID는 결과에서 빠짐
    @Transactional(readOnly = true)
    public List<MemberDto> getMembersByIds(Collection<Long> memberIds) {
        return memberSupport.getMembers(memberIds).stream().map(Member::toDto).toList();
    }

    // 이메일로 회원 조회 (다른 컨텍스트용)
    @Transactional(readOnly = true)
    public Optional<MemberDto> findMemberByEmail(String email) {
        return memberSupport.findMemberByEmail(email).map(Member::toDto);
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

    // 관리자 회원 목록
    @Transactional(readOnly = true)
    public PageResponse<AdminMemberDto> getMembers(String email, MemberStatus status, MemberType memberType, Pageable pageable) {
        return memberGetMembersUseCase.getMembers(email, status, memberType, pageable);
    }

    // 관리자 회원 상세
    @Transactional(readOnly = true)
    public AdminMemberDetailDto getMemberDetail(Long memberId) {
        return memberGetMemberDetailUseCase.getMemberDetail(memberId);
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

    // 탈퇴 조건 조회
    @Transactional(readOnly = true)
    public WithdrawalCheckDto getWithdrawalCheck(Long memberId) {
        return memberGetWithdrawalCheckUseCase.getWithdrawalCheck(memberId);
    }

    // 회원 탈퇴 (비식별화까지 한 트랜잭션)
    @Transactional
    public void withdraw(Long memberId, String password) {
        memberWithdrawUseCase.withdraw(memberId, password);
    }
}
