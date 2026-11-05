package com.backend.monolith.client;

import org.springframework.stereotype.Component;

import com.backend.shared.settlement.out.SettlementApi;

/**
 * 정산이 settlement-service로 옮겨 간 뒤 루트 모놀리스에 남은 임시 구현.
 * 회원이 쓰는 두 메서드 모두 예외를 던진다.
 * - holdSeller: 정산서 상태를 바꾸는 호출이라 아무것도 안 한 채 성공하면 안 된다.
 * - getUnsettledAmount: 판매자 철회·회원 탈퇴를 막는 검사에 쓰인다. 0을 돌려주면 미정산 금액이 있는 판매자도 철회·탈퇴가 통과된다.
 * 그래서 연결 전까지 판매자 제재, 철회 요청·승인, 판매자 회원 탈퇴, 내 판매자 정보 조회·수정이 실패한다.
 * TODO 서비스 간 연결 작업에서 교체
 *  - getUnsettledAmount: settlement-service 내부 API(GET /internal/v1/settlements/sellers/{sellerId}/unsettled-amount) 호출
 *  - holdSeller: #205의 SellerSanctionedEvent(seller.events)로 전환되면 이 메서드 자체가 필요 없어진다
 *  (회원 서비스 이동 때 그쪽으로 옮김)
 */
@Component
public class SettlementApiClient implements SettlementApi {

    @Override
    public long getUnsettledAmount(Long sellerId) {
        throw notConnected();
    }

    @Override
    public void holdSeller(Long sellerId, String reason) {
        throw notConnected();
    }

    private UnsupportedOperationException notConnected() {
        return new UnsupportedOperationException("settlement-service 연결 전이라 정산 API를 호출할 수 없습니다");
    }
}
