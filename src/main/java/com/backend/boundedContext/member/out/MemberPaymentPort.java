package com.backend.boundedContext.member.out;

import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 회원 컨텍스트가 쓰는 결제 기능만 담은 Port
 * 지금은 같은 앱의 PaymentApi를 부르고, 결제 모듈이 분리되면 결제 내부 API(/internal/v1/payments)를 부르는 HTTP 클라이언트로 바꿈
 */
public interface MemberPaymentPort {

    // 잔액과 홀딩 중 금액 조회 (탈퇴 검사, 관리자 회원 상세)
    WalletSummaryDto getWallet(Long memberId);

    // 결과가 아직 정해지지 않은 충전 건이 있는지 (탈퇴 검사)
    boolean hasPendingTopups(Long memberId);
}
