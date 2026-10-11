package com.backend.shared.payment.out;

import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 결제 컨텍스트가 다른 컨텍스트에 제공하는 내부 API.
 * 나머지 메서드(pay, transfer 등)는 계약 문서 5장을 기준으로 각 기능을 구현할 때 추가
 */
public interface PaymentApi {

    // 잔액과 홀딩 중 금액 조회
    WalletSummaryDto getWallet(Long memberId);
}
