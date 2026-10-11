package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 입점 신청.
 * 진행 중인 신청 중복은 먼저 조회로 막고, 동시에 들어온 요청은 부분 유니크 인덱스가 막는다.
 */
@Component
@RequiredArgsConstructor
public class MemberApplySellerUseCase {
    private final SellerRepository sellerRepository;
    private final MemberSupport memberSupport;

    public SellerApplyResponse apply(Long memberId, SellerApplyRequest request) {
        Member member = memberSupport.getMember(memberId);

        Seller seller = Seller.apply(
                member,
                request.businessName(),
                request.businessNumber(),
                request.representativeName(),
                request.phoneNumber(),
                request.bankName(),
                request.accountNumber(),
                request.accountHolder()
        );

        // 이 회원이 이미 심사 중·판매 중·철회 심사 중
        if (sellerRepository.existsByMember_IdAndStatusIn(memberId, Seller.ONGOING_STATUSES)) {
            throw new DomainException(MemberErrorCode.SELLER_ALREADY_APPLIED);
        }

        // 다른 회원이 이 사업자번호로 진행 중
        if (sellerRepository.existsByBusinessNumberAndStatusIn(seller.getBusinessNumber(), Seller.ONGOING_STATUSES)) {
            throw new DomainException(MemberErrorCode.BUSINESS_NUMBER_DUPLICATED);
        }

        return SellerApplyResponse.from(sellerRepository.saveAndFlush(seller));
    }
}
