package com.backend.shared.member.out;

import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.SellerDto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 회원 컨텍스트가 다른 컨텍스트에 제공하는 내부 API
 */
public interface MemberApi {

    // 회원 조회
    MemberDto getMember(Long memberId);

    // 판매자 신청 정보 조회. 신청한 적이 없으면 empty
    Optional<SellerDto> findSellerByMemberId(Long memberId);

    // 판매 기능을 쓸 수 있는(APPROVED) 판매자 조회. 판매 기능을 쓸 수 없는 상태면 403 SELLER_NOT_APPROVED
    SellerDto getApprovedSellerByMemberId(Long memberId);

    // 판매자 조회. 없으면 404 SELLER_NOT_FOUND
    SellerDto getSeller(Long sellerId);

    // 여러 판매자 조회. 없는 ID는 결과에서 빠짐
    List<SellerDto> getSellers(Collection<Long> sellerIds);
}
