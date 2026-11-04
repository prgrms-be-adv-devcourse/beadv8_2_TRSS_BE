package com.backend.boundedContext.member.out;

/**
 * 회원 컨텍스트가 쓰는 정산 기능만 담은 Port
 * 지금은 같은 앱의 SettlementApi를 부르고, 정산 모듈이 분리되면 정산 내부 API를 부르는 HTTP 클라이언트로 바꿈
 */
public interface MemberSettlementPort {

    // 판매자가 아직 정산받지 않은 판매 금액 (탈퇴 검사, 판매자 철회 요청·승인, 내 판매자 정보)
    long getUnsettledAmount(Long sellerId);

    // 관리자 제재 시 판매자 정산 보류 (같은 트랜잭션에서 호출함. 이벤트 전환은 #201 3단계)
    void holdSeller(Long sellerId, String reason);
}
