package com.backend.boundedContext.member.out;

/**
 * 회원 컨텍스트가 쓰는 정산 기능만 담은 Port
 * 미지급 금액은 정산 내부 API(/internal/v1/settlements)를 HTTP로 부름 (MemberSettlementHttpAdapter). 정산 보류는 #225에서 이벤트로만 바뀜
 * 정산 모듈을 부르지 못하면(시간 초과·연결 실패·5xx) MemberPortUnavailableException, 정산 모듈이 오류로 응답하면 같은 resultCode의 DomainException을 던짐
 */
public interface MemberSettlementPort {

    // 판매자가 아직 정산받지 않은 판매 금액 (탈퇴 검사, 판매자 철회 요청·승인, 내 판매자 정보)
    long getUnsettledAmount(Long sellerId);

    // 관리자 제재 시 판매자 정산 보류 (같은 트랜잭션에서 호출함. 정산이 SellerSanctionedEvent 컨슈머로 받게 되면 뺌)
    void holdSeller(Long sellerId, String reason);
}
