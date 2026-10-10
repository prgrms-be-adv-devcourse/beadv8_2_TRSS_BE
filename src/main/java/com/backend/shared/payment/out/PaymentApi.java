package com.backend.shared.payment.out;

/**
 * 결제 컨텍스트 계약. 나머지 메서드는 결제 담당이 추가
 */
public interface PaymentApi {

    // 회원 가입 트랜잭션 안에서 잔액 0인 지갑을 생성 (이미 있으면 무시)
    void createWallet(Long memberId);
}
