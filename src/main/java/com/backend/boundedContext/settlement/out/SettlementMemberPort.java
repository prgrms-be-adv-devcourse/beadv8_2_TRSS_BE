package com.backend.boundedContext.settlement.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.backend.shared.member.dto.SellerDto;

/**
 * 정산 컨텍스트가 쓰는 회원(판매자) 기능만 담은 Port
 * 지금은 같은 앱의 MemberApi를 부르고, 정산 모듈이 분리되면 회원 내부 API(/internal/v1/sellers)를 부르는 HTTP 클라이언트로 바꿈
 */
public interface SettlementMemberPort {

    // 판매자 1명 조회 (지급 시 판매자 회원 ID). 없으면 404-SELLER_NOT_FOUND
    SellerDto getSeller(Long sellerId);

    // 여러 판매자 한 번에 조회 (관리자 목록 상호명, 미리보기, 집계 시 제재 여부). 없는 ID는 결과에서 빠짐
    List<SellerDto> getSellers(Collection<Long> sellerIds);

    // 로그인 회원의 판매자 정보 (판매자 정산 API, 시드 데이터). 신청한 적이 없으면 empty
    Optional<SellerDto> findSellerByMemberId(Long memberId);
}
