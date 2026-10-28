package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentPrepareTopupUseCase {

    private final PaymentPolicy paymentPolicy;
    private final WalletRepository walletRepository;
    private final TopupRepository topupRepository;
    private final MemberApi memberApi;

    // 충전 준비: 금액 검증 후 토스 주문번호를 발급해 READY로 저장
    // 탈퇴한 회원은 남은 Access Token으로 충전할 수 없게 401 (탈퇴한 회원 지갑에 돈이 들어가지 않도록)
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        memberApi.checkActiveMember(memberId);
        paymentPolicy.validateTopupAmount(amount);

        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Topup topup = topupRepository.save(Topup.create(memberId, wallet, amount));

        return TopupPrepareDto.from(topup);
    }
}
