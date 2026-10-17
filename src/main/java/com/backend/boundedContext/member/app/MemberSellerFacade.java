package com.backend.boundedContext.member.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminSellerListDto;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;

import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트의 판매자 기능 (입점 신청·조회·수정, 관리자 판매자 관리, 다른 컨텍스트용 판매자 조회).
 * 회원·인증 기능은 {@link MemberFacade}
 */
@Service
@RequiredArgsConstructor
public class MemberSellerFacade {

    private final MemberApplySellerUseCase memberApplySellerUseCase;
    private final MemberGetMySellerUseCase memberGetMySellerUseCase;
    private final MemberUpdateSellerUseCase memberUpdateSellerUseCase;
    private final MemberRequestWithdrawalUseCase memberRequestWithdrawalUseCase;
    private final MemberGetSellerUseCase memberGetSellerUseCase;
    private final MemberCreateSeedSellerUseCase memberCreateSeedSellerUseCase;
    private final MemberGetSellersUseCase memberGetSellersUseCase;
    private final MemberGetSellerDetailUseCase memberGetSellerDetailUseCase;
    private final MemberApproveSellerUseCase memberApproveSellerUseCase;
    private final MemberRejectSellerUseCase memberRejectSellerUseCase;
    private final MemberApproveWithdrawalUseCase memberApproveWithdrawalUseCase;
    private final MemberRejectWithdrawalUseCase memberRejectWithdrawalUseCase;
    private final MemberSanctionSellerUseCase memberSanctionSellerUseCase;

    // 판매자 입점 신청 (처음이면 생성, 반려·본인 철회 뒤면 같은 판매자로 재신청. 둘 다 PENDING)
    @Transactional
    public SellerApplyResponse applySeller(Long memberId, SellerApplyRequest request) {
        return memberApplySellerUseCase.apply(memberId, request);
    }

    // 내 판매자 정보·신청 상태 조회 (계좌는 끝 4자리)
    @Transactional(readOnly = true)
    public MySellerResponse getMySeller(Long memberId) {
        return memberGetMySellerUseCase.getMySeller(memberId);
    }

    // 판매자 정보 수정 (상호·연락처·정산 계좌)
    @Transactional
    public MySellerResponse updateSeller(Long memberId, SellerUpdateRequest request) {
        return memberUpdateSellerUseCase.update(memberId, request);
    }

    // 판매자 본인 철회 요청 (APPROVED → WITHDRAW_REQUESTED, 미정산 금액이 있으면 불가)
    @Transactional
    public MySellerResponse requestWithdrawal(Long memberId, String reason) {
        return memberRequestWithdrawalUseCase.requestWithdrawal(memberId, reason);
    }

    // 회원의 판매자 정보 (다른 컨텍스트용, 없으면 empty)
    @Transactional(readOnly = true)
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return memberGetSellerUseCase.findSellerByMemberId(memberId);
    }

    // 판매 가능한(APPROVED) 판매자 조회 (다른 컨텍스트용, 아니면 403)
    @Transactional(readOnly = true)
    public SellerDto getApprovedSellerByMemberId(Long memberId) {
        return memberGetSellerUseCase.getApprovedSellerByMemberId(memberId);
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

    // 관리자 판매자 목록 + 탭별 건수
    @Transactional(readOnly = true)
    public AdminSellerListDto getSellersForAdmin(SellerStatus status, Pageable pageable) {
        return memberGetSellersUseCase.getSellers(status, pageable);
    }

    // 관리자 판매자 상세 (판매자 정보 + 상태 이력, 계좌는 끝 4자리)
    @Transactional(readOnly = true)
    public AdminSellerDetailDto getSellerDetail(Long sellerId) {
        return memberGetSellerDetailUseCase.getSellerDetail(sellerId);
    }

    // 관리자 입점 승인 (PENDING → APPROVED, 회원 role을 SELLER로)
    @Transactional
    public AdminSellerDetailDto approveSeller(Long sellerId) {
        return memberApproveSellerUseCase.approve(sellerId);
    }

    // 관리자 입점 반려 (PENDING → REJECTED, 사유 필수, 신청서 스냅샷 후 사업자번호 비움)
    @Transactional
    public AdminSellerDetailDto rejectSeller(Long sellerId, String reason) {
        return memberRejectSellerUseCase.reject(sellerId, reason);
    }

    // 관리자 철회 승인 (WITHDRAW_REQUESTED → WITHDRAWN, 진행 중인 판매·미정산 금액이 있으면 불가)
    @Transactional
    public AdminSellerDetailDto approveWithdrawal(Long sellerId) {
        return memberApproveWithdrawalUseCase.approveWithdrawal(sellerId);
    }

    // 관리자 철회 거절 (WITHDRAW_REQUESTED → APPROVED, 사유 필수)
    @Transactional
    public AdminSellerDetailDto rejectWithdrawal(Long sellerId, String reason) {
        return memberRejectWithdrawalUseCase.rejectWithdrawal(sellerId, reason);
    }

    // 관리자 제재 (APPROVED·WITHDRAW_REQUESTED → SANCTIONED, 사유 필수, 정산 보류)
    @Transactional
    public AdminSellerDetailDto sanctionSeller(Long sellerId, String reason) {
        return memberSanctionSellerUseCase.sanction(sellerId, reason);
    }

    // 개발용 시드 판매자 (시드 판매자 회원을 APPROVED 판매자로)
    @Transactional
    public void createSeedSeller(String sellerEmail) {
        memberCreateSeedSellerUseCase.createSeedSeller(sellerEmail);
    }
}
