package com.backend.boundedContext.payment.app;

import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.app.dto.TopupFailDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.global.page.PageResponse;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;
    private final PaymentGetWalletUseCase paymentGetWalletUseCase;
    private final PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;
    private final PaymentConfirmTopupUseCase paymentConfirmTopupUseCase;
    private final PaymentPayUseCase paymentPayUseCase;
    private final PaymentFailTopupUseCase paymentFailTopupUseCase;
    private final PaymentGetTopupsUseCase paymentGetTopupsUseCase;
    private final PaymentGetTopupUseCase paymentGetTopupUseCase;
    private final PaymentTransferUseCase paymentTransferUseCase;
    private final PaymentFindTransferUseCase paymentFindTransferUseCase;
    private final PaymentCreateSeedHoldingsUseCase paymentCreateSeedHoldingsUseCase;

    // 지갑 생성 (진행 중인 트랜잭션이 있으면 참여, 없으면 새로 시작)
    @Transactional
    public void createWallet(Long memberId) {
        paymentCreateWalletUseCase.createWallet(memberId);
    }

    // 지갑 잔액 요약
    @Transactional(readOnly = true)
    public WalletSummaryDto getWallet(Long memberId) {
        return paymentGetWalletUseCase.getWallet(memberId);
    }

    // 충전 준비
    @Transactional
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        return paymentPrepareTopupUseCase.prepareTopup(memberId, amount);
    }

    // 충전 승인. 토스 호출을 DB 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음 (트랜잭션은 PaymentTopupConfirmProcessor의 Tx1·Tx2에서 각각 실행)
    public TopupConfirmDto confirmTopup(Long memberId, String paymentKey, String orderId, long amount) {
        return paymentConfirmTopupUseCase.confirmTopup(memberId, paymentKey, orderId, amount);
    }

    // 주문 결제 (주문 결제 트랜잭션에 참여, 주문 쪽이 실패하면 함께 롤백)
    @Transactional
    public PayResultDto pay(PayCommand command) {
        return paymentPayUseCase.pay(command);
    }

    // 충전 실패 기록 (토스 결제창에서 실패·취소)
    @Transactional
    public TopupFailDto failTopup(Long memberId, Long topupId, String code, String message) {
        return paymentFailTopupUseCase.failTopup(memberId, topupId, code, message);
    }

    // 내 충전 목록
    @Transactional(readOnly = true)
    public PageResponse<TopupDto> getTopups(Long memberId, Pageable pageable) {
        return paymentGetTopupsUseCase.getTopups(memberId, pageable);
    }

    // 내 충전 건 상세
    @Transactional(readOnly = true)
    public TopupDto getTopup(Long memberId, Long topupId) {
        return paymentGetTopupUseCase.getTopup(memberId, topupId);
    }

    // 정산 지급 (정산 지급 트랜잭션에 참여, 실패하면 함께 롤백)
    @Transactional
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return paymentTransferUseCase.transfer(command);
    }

    // 정산 지급 내역 조회
    @Transactional(readOnly = true)
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        return paymentFindTransferUseCase.findTransfer(idempotencyKey);
    }

    // 개발용 HELD 홀딩 시드
    @Transactional
    public void createSeedHoldings(Long buyerMemberId, Map<Long, Long> amountByOrderId) {
        paymentCreateSeedHoldingsUseCase.createSeedHoldings(buyerMemberId, amountByOrderId);
    }
}
