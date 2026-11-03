package com.backend.boundedContext.member.out;

import org.springframework.stereotype.Component;

import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 PaymentApi로 MemberPaymentPort를 구현함 (결제 모듈 분리 전까지 사용)
 */
@Component
@RequiredArgsConstructor
public class PaymentApiMemberPaymentAdapter implements MemberPaymentPort {

    private final PaymentApi paymentApi;

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        return paymentApi.getWallet(memberId);
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        return paymentApi.hasPendingTopups(memberId);
    }
}
