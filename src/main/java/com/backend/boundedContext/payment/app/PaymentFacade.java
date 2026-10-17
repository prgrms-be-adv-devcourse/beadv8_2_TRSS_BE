package com.backend.boundedContext.payment.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.AdminPaymentDto;
import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.app.dto.TopupFailDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.app.dto.WalletTransactionDto;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.global.page.PageResponse;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;
    private final PaymentGetWalletUseCase paymentGetWalletUseCase;
    private final PaymentGetWalletTransactionsUseCase paymentGetWalletTransactionsUseCase;
    private final PaymentGetAdminPaymentsUseCase paymentGetAdminPaymentsUseCase;
    private final PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;
    private final PaymentConfirmTopupUseCase paymentConfirmTopupUseCase;
    private final PaymentPayUseCase paymentPayUseCase;
    private final PaymentGetPaymentAttemptsUseCase paymentGetPaymentAttemptsUseCase;
    private final PaymentFailTopupUseCase paymentFailTopupUseCase;
    private final PaymentGetTopupsUseCase paymentGetTopupsUseCase;
    private final PaymentGetTopupUseCase paymentGetTopupUseCase;
    private final PaymentExpireTopupsUseCase paymentExpireTopupsUseCase;
    private final PaymentReconcileTopupsUseCase paymentReconcileTopupsUseCase;
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

    // 내 예치금 거래 내역
    @Transactional(readOnly = true)
    public PageResponse<WalletTransactionDto> getWalletTransactions(Long memberId, WalletTransactionType type, LocalDate from, LocalDate to, Pageable pageable) {
        return paymentGetWalletTransactionsUseCase.getTransactions(memberId, type, from, to, pageable);
    }

    // 관리자 결제 조회 결제 탭
    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentDto> getAdminPayments(String keyword, LocalDate from, LocalDate to, Pageable pageable) {
        return paymentGetAdminPaymentsUseCase.getPayments(keyword, from, to, pageable);
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

    // 주문의 결제 시도 목록 (주문 상세의 결제 내역)
    @Transactional(readOnly = true)
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        return paymentGetPaymentAttemptsUseCase.getPaymentAttempts(orderId);
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

    // READY 충전 건 만료 (스케줄러)
    @Transactional
    public int expireReadyTopups() {
        return paymentExpireTopupsUseCase.expireReadyTopups(LocalDateTime.now());
    }

    // PROCESSING 충전 건 결과 확인 (스케줄러). 토스 조회를 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음
    // (반영·실패 기록은 PaymentTopupConfirmProcessor에서 건마다 짧은 트랜잭션으로 실행)
    public TopupReconcileResult reconcileProcessingTopups() {
        return paymentReconcileTopupsUseCase.reconcileProcessingTopups(LocalDateTime.now());
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
