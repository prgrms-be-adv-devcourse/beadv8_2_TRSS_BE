package com.backend.boundedContext.member.out;

/**
 * 회원 컨텍스트가 쓰는 상품 기능만 담은 Port
 * 지금은 같은 앱의 ProductApi를 부르고, 상품 모듈이 분리되면 상품 내부 API를 부르는 HTTP 클라이언트로 바꿈
 * 두 기능 모두 같은 트랜잭션에서 호출함. 다시 호출해도 결과가 같아서 이벤트로 바꿔도 중복 전달에 안전함 (이벤트 전환은 #201 3단계)
 */
public interface MemberProductPort {

    // 판매자의 판매 중 상품을 모두 중지하고, 그 상품이 든 결제 대기 주문을 취소함 (판매자 철회 요청, 관리자 제재)
    void stopAllBySeller(Long sellerId, String reason);

    // 판매자 철회로 중지된 상품을 다시 판매 중으로 되돌림 (판매자 철회 반려)
    void resumeAllBySeller(Long sellerId);
}
