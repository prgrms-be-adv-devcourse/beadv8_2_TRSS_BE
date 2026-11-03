package com.backend.market.client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;

/**
 * member-service 연결 전 임시 구현.
 * market이 쓰는 조회는 로컬에서 상품 등록·장바구니 흐름이 돌아가도록 더미 값을 돌려준다(회원 ID = 판매자 ID, 항상 APPROVED).
 * market이 쓰지 않는 메서드는 UnsupportedOperationException.
 * TODO 서비스 간 연결 작업에서 member-service 내부 API(/internal/**)를 RestClient로 호출하도록 교체
 */
@Component
public class MemberApiClient implements MemberApi {

    private static final String DUMMY_BUSINESS_NAME = "임시 판매자";

    // ── market이 쓰는 메서드: 더미 ──

    @Override
    public void checkActiveMember(Long memberId) {
        // 연결 전에는 모든 회원을 활성 회원으로 본다
    }

    @Override
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return Optional.of(dummySeller(memberId));
    }

    @Override
    public SellerDto getApprovedSellerByMemberId(Long memberId) {
        return dummySeller(memberId);
    }

    @Override
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        return sellerIds.stream().map(this::dummySeller).toList();
    }

    // ── market이 쓰지 않는 메서드 ──

    @Override
    public MemberDto getMember(Long memberId) {
        throw notUsedInMarket();
    }

    @Override
    public List<MemberDto> getMembers(Collection<Long> memberIds) {
        throw notUsedInMarket();
    }

    @Override
    public Optional<MemberDto> findMemberByEmail(String email) {
        throw notUsedInMarket();
    }

    @Override
    public SellerDto getSeller(Long sellerId) {
        throw notUsedInMarket();
    }

    private SellerDto dummySeller(Long id) {
        return new SellerDto(id, id, DUMMY_BUSINESS_NAME, SellerStatus.APPROVED);
    }

    private UnsupportedOperationException notUsedInMarket() {
        return new UnsupportedOperationException("market-service에서 쓰지 않는 MemberApi 메서드");
    }
}
