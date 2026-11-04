package com.backend.settlement.client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;

/**
 * member-service 연결 전 임시 구현.
 * 판매자 ID와 회원 ID는 서로 다른 값이라 지어낸 판매자를 돌려주면 남의 정산서가 보이거나 지급 대상이 바뀐다.
 * 그래서 판매자를 모르는 것으로 답한다: 목록 조회는 빈 목록, 회원 ID로 찾기는 empty(판매자 아님),
 * 지급에 쓰는 getSeller는 예외(PaymentApiClient.transfer처럼 연결 전에는 실패해야 함).
 * 정산이 쓰지 않는 메서드도 UnsupportedOperationException.
 * 정산 코드는 SettlementMemberPort → SettlementMemberApiAdapter를 거쳐 이 구현을 부른다.
 * TODO 서비스 간 연결 작업에서 member-service 내부 API(/internal/v1/sellers)를 RestClient로 호출하도록 교체
 */
@Component
public class MemberApiClient implements MemberApi {

    // ── 정산이 쓰는 메서드: 판매자를 모르는 것으로 답함 ──

    // 지급 대상(판매자 회원 ID)을 정하는 호출이라 지어낸 값을 돌려주지 않는다
    @Override
    public SellerDto getSeller(Long sellerId) {
        throw new UnsupportedOperationException("member-service 연결 전이라 판매자를 조회할 수 없습니다");
    }

    // 없는 ID는 결과에서 빠지는 계약이라 빈 목록 (관리자 목록·미리보기의 상호명은 비고, 집계는 제재되지 않은 판매자로 봄)
    @Override
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        return List.of();
    }

    // 판매자 신청 이력이 없는 것으로 답함 (판매자 정산 조회는 빈 목록·상세 404, 시드 데이터는 건너뜀)
    @Override
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return Optional.empty();
    }

    // ── 정산이 쓰지 않는 메서드 ──

    @Override
    public MemberDto getMember(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public void checkActiveMember(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public List<MemberDto> getMembers(Collection<Long> memberIds) {
        throw notUsedInSettlement();
    }

    @Override
    public Optional<MemberDto> findMemberByEmail(String email) {
        throw notUsedInSettlement();
    }

    @Override
    public SellerDto getApprovedSellerByMemberId(Long memberId) {
        throw notUsedInSettlement();
    }

    private UnsupportedOperationException notUsedInSettlement() {
        return new UnsupportedOperationException("settlement-service에서 쓰지 않는 MemberApi 메서드");
    }
}
