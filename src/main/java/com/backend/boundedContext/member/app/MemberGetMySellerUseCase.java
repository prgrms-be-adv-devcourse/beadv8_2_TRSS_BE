package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 내 판매자 정보 조회. 가장 최근 신청을 보여 준다 (반려·철회된 신청이면 그 상태와 사유가 보임)
@Component
@RequiredArgsConstructor
public class MemberGetMySellerUseCase {
    private final SellerRepository sellerRepository;

    public MySellerResponse getMySeller(Long memberId) {
        return sellerRepository.findFirstByMember_IdOrderByIdDesc(memberId)
                .map(MySellerResponse::from)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));
    }
}
