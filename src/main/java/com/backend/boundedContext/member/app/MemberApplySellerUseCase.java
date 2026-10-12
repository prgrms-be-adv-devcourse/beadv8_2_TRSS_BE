package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 입점 신청. 처음이면 판매자를 만들고, 반려·본인 철회 뒤라면 같은 판매자로 재신청한다.
 * 중복은 먼저 조회로 막고, 동시에 들어온 요청은 DB가 막는다
 * (처음 신청: member_id·business_number UNIQUE, 재신청: @Version).
 */
@Component
@RequiredArgsConstructor
public class MemberApplySellerUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final MemberSupport memberSupport;

    public SellerApplyResponse apply(Long memberId, SellerApplyRequest request) {
        Member member = memberSupport.getMember(memberId);

        return sellerRepository.findByMemberId(memberId)
                .map(seller -> reapply(seller, request))
                .orElseGet(() -> applyFirst(member, request));
    }

    // 처음 신청: 판매자를 만들고 첫 이력(null → PENDING)을 남긴다
    private SellerApplyResponse applyFirst(Member member, SellerApplyRequest request) {
        checkBusinessNumberAvailable(request.businessNumber());

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
        sellerRepository.saveAndFlush(seller);
        sellerStatusHistoryRepository.save(SellerStatusHistory.applied(seller));
        return SellerApplyResponse.from(seller);
    }

    // 재신청: 반려·본인 철회 뒤에만 같은 판매자를 다시 PENDING으로
    private SellerApplyResponse reapply(Seller seller, SellerApplyRequest request) {
        if (seller.getStatus() == SellerStatus.SANCTIONED) {
            throw new DomainException(MemberErrorCode.SELLER_SANCTIONED);
        }
        // 이 회원이 이미 심사 중·판매 중·철회 심사 중
        if (!seller.canReapply()) {
            throw new DomainException(MemberErrorCode.SELLER_ALREADY_APPLIED);
        }
        checkBusinessNumberAvailable(request.businessNumber());

        SellerStatusHistory history = seller.reapply(
                request.businessName(),
                request.businessNumber(),
                request.representativeName(),
                request.phoneNumber(),
                request.bankName(),
                request.accountNumber(),
                request.accountHolder()
        );
        sellerStatusHistoryRepository.save(history);
        return SellerApplyResponse.from(sellerRepository.saveAndFlush(seller));
    }

    // 다른 판매자가 이 사업자번호로 심사 중·판매 중·철회 심사 중이거나 제재됨
    private void checkBusinessNumberAvailable(String businessNumber) {
        if (sellerRepository.existsByBusinessNumber(Seller.digitsOnly(businessNumber))) {
            throw new DomainException(MemberErrorCode.BUSINESS_NUMBER_DUPLICATED);
        }
    }
}
