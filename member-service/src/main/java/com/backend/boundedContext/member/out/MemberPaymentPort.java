package com.backend.boundedContext.member.out;

import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 회원 컨텍스트가 쓰는 결제 기능만 담은 Port
 * 결제 내부 API(/internal/v1/payments)를 HTTP로 부름 (MemberPaymentHttpAdapter)
 * 결제 모듈을 부르지 못하면(시간 초과·연결 실패·5xx) MemberPortUnavailableException, 결제 모듈이 오류로 응답하면 같은 resultCode의 DomainException을 던짐
 */
public interface MemberPaymentPort {

    // 잔액과 홀딩 중 금액 조회 (탈퇴 검사, 관리자 회원 상세)
    WalletSummaryDto getWallet(Long memberId);

    // 결과가 아직 정해지지 않은 충전 건이 있는지 (탈퇴 검사)
    boolean hasPendingTopups(Long memberId);
}
