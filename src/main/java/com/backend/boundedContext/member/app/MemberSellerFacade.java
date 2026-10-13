package com.backend.boundedContext.member.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.shared.member.dto.SellerDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트의 판매자 기능 (입점 신청·조회·수정, 다른 컨텍스트용 판매자 조회).
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

    // 개발용 시드 판매자 (시드 판매자 회원을 APPROVED 판매자로)
    @Transactional
    public void createSeedSeller(String sellerEmail) {
        memberCreateSeedSellerUseCase.createSeedSeller(sellerEmail);
    }
}
