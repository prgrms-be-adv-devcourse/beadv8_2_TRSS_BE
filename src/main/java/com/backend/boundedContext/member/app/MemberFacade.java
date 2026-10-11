package com.backend.boundedContext.member.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerDto;

import lombok.RequiredArgsConstructor;

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
    private final MemberSupport memberSupport;
    private final MemberApplySellerUseCase memberApplySellerUseCase;
    private final MemberGetMySellerUseCase memberGetMySellerUseCase;
    private final MemberGetSellerUseCase memberGetSellerUseCase;

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

    // 판매자 입점 신청 (PENDING으로 생성)
    @Transactional
    public SellerApplyResponse applySeller(Long memberId, SellerApplyRequest request) {
        return memberApplySellerUseCase.apply(memberId, request);
    }

    // 내 판매자 정보·신청 상태 조회 (계좌는 끝 4자리)
    @Transactional(readOnly = true)
    public MySellerResponse getMySeller(Long memberId) {
        return memberGetMySellerUseCase.getMySeller(memberId);
    }

    // 회원의 판매자 정보 (다른 컨텍스트용, 최신 1건, 없으면 empty)
    @Transactional(readOnly = true)
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return memberGetSellerUseCase.findSellerByMemberId(memberId);
    }

    // 판매자 조회 (다른 컨텍스트용, 없으면 404)
    @Transactional(readOnly = true)
    public SellerDto getSeller(Long sellerId) {
        return memberGetSellerUseCase.getSeller(sellerId);
    }

    // 여러 판매자 조회 (다른 컨텍스트용, 없는 ID는 빠짐)
    @Transactional(readOnly = true)
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        return memberGetSellerUseCase.getSellers(sellerIds);
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
    public PageResponse<AdminMemberDto> getMembers(String email, MemberStatus status, MemberRole role, Pageable pageable) {
        return memberGetMembersUseCase.getMembers(email, status, role, pageable);
    }
}
