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
import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupManualResolveDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupPgPaymentDto;
import com.backend.boundedContext.payment.app.dto.RefundDto;
import com.backend.boundedContext.payment.app.dto.TopupCancelDto;
import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.app.dto.TopupFailDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.app.dto.WalletTransactionDto;
import com.backend.boundedContext.payment.domain.TopupManualResolveAction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.order.event.OrderReturnedEvent;
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
    private final PaymentCheckPendingTopupsUseCase paymentCheckPendingTopupsUseCase;
    private final PaymentGetWalletTransactionsUseCase paymentGetWalletTransactionsUseCase;
    private final PaymentGetAdminPaymentsUseCase paymentGetAdminPaymentsUseCase;
    private final PaymentGetAdminTopupsUseCase paymentGetAdminTopupsUseCase;
    private final PaymentGetFailuresUseCase paymentGetFailuresUseCase;
    private final PaymentGetTopupFailuresUseCase paymentGetTopupFailuresUseCase;
    private final PaymentGetAdminTopupPgPaymentUseCase paymentGetAdminTopupPgPaymentUseCase;
    private final PaymentResolveTopupManualCheckUseCase paymentResolveTopupManualCheckUseCase;
    private final PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;
    private final PaymentConfirmTopupUseCase paymentConfirmTopupUseCase;
    private final PaymentCancelTopupUseCase paymentCancelTopupUseCase;
    private final PaymentPayUseCase paymentPayUseCase;
    private final PaymentCancelPaymentUseCase paymentCancelPaymentUseCase;
    private final PaymentGetPaymentAttemptsUseCase paymentGetPaymentAttemptsUseCase;
    private final PaymentFailTopupUseCase paymentFailTopupUseCase;
    private final PaymentGetTopupsUseCase paymentGetTopupsUseCase;
    private final PaymentGetTopupUseCase paymentGetTopupUseCase;
    private final PaymentExpireTopupsUseCase paymentExpireTopupsUseCase;
    private final PaymentReconcileTopupsUseCase paymentReconcileTopupsUseCase;
    private final PaymentReconcileCancelingTopupsUseCase paymentReconcileCancelingTopupsUseCase;
    private final PaymentReconcileMethodCancelingTopupsUseCase paymentReconcileMethodCancelingTopupsUseCase;
    private final PaymentWarnStuckTopupsUseCase paymentWarnStuckTopupsUseCase;
    private final PaymentTransferUseCase paymentTransferUseCase;
    private final PaymentFindTransferUseCase paymentFindTransferUseCase;
    private final PaymentCreateSeedHoldingsUseCase paymentCreateSeedHoldingsUseCase;
    private final PaymentRequestRefundUseCase paymentRequestRefundUseCase;
    private final PaymentRefundUseCase paymentRefundUseCase;
    private final PaymentRefundFailureRecorder paymentRefundFailureRecorder;
    private final PaymentGetRefundUseCase paymentGetRefundUseCase;
    private final PaymentPayReturnPickupFeeUseCase paymentPayReturnPickupFeeUseCase;

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

    // 결과가 정해지지 않은 충전 건(READY·PROCESSING·CANCELING·METHOD_CANCELING)이 있는지
    @Transactional(readOnly = true)
    public boolean hasPendingTopups(Long memberId) {
        return paymentCheckPendingTopupsUseCase.hasPendingTopups(memberId);
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

    // 관리자 실패 거래(A7)의 결제, 환불 행 (공통 형식). type이 없으면 결제 실패와 환불 실패를 합쳐 최신순
    @Transactional(readOnly = true)
    public PageResponse<AdminFailureItem> getAdminFailures(AdminFailureType type, LocalDate from, LocalDate to,
                                                           Pageable pageable) {
        return paymentGetFailuresUseCase.getFailures(type, from, to, pageable);
    }

    // 관리자 실패 거래의 충전 확인 지연 행 (공통 형식)
    @Transactional(readOnly = true)
    public PageResponse<AdminFailureItem> getAdminTopupFailures(LocalDate from, LocalDate to, Pageable pageable) {
        return paymentGetTopupFailuresUseCase.getFailures(from, to, pageable, LocalDateTime.now());
    }

    // 관리자 충전 PG 결제 조회. PG를 부르므로 트랜잭션을 걸지 않음
    public AdminTopupPgPaymentDto getAdminTopupPgPayment(Long topupId) {
        return paymentGetAdminTopupPgPaymentUseCase.getPgPayment(topupId);
    }

    // 관리자 충전 수동 확인 정리. 다시 요청은 PG를 부르므로 트랜잭션을 걸지 않음 (반영은 처리기의 트랜잭션 메서드로)
    public AdminTopupManualResolveDto resolveTopupManualCheck(Long adminId, Long topupId, TopupManualResolveAction action) {
        return paymentResolveTopupManualCheckUseCase.resolve(adminId, topupId, action);
    }

    // 관리자 결제 조회 충전 탭
    @Transactional(readOnly = true)
    public PageResponse<AdminTopupDto> getAdminTopups(String keyword, LocalDate from, LocalDate to, Pageable pageable) {
        return paymentGetAdminTopupsUseCase.getTopups(keyword, from, to, pageable);
    }

    // 충전 준비
    @Transactional
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        return paymentPrepareTopupUseCase.prepareTopup(memberId, amount);
    }

    // 충전 승인. PG 호출을 DB 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음 (트랜잭션은 PaymentTopupConfirmProcessor의 Tx1·Tx2에서 각각 실행)
    public TopupConfirmDto confirmTopup(Long memberId, String paymentKey, String orderId, long amount) {
        return paymentConfirmTopupUseCase.confirmTopup(memberId, paymentKey, orderId, amount);
    }

    // 충전 취소. PG 호출을 DB 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음 (트랜잭션은 PaymentTopupCancelProcessor의 Tx1·Tx2에서 각각 실행)
    public TopupCancelDto cancelTopup(Long memberId, Long topupId) {
        return paymentCancelTopupUseCase.cancelTopup(memberId, topupId);
    }

    // 주문 결제 (주문 결제 트랜잭션에 참여, 주문 쪽이 실패하면 함께 롤백)
    @Transactional
    public PayResultDto pay(PayCommand command) {
        return paymentPayUseCase.pay(command);
    }

    // 결제 취소 (결제 후 주문 처리가 실패했을 때 주문 쪽 보상). 결제할 때 쓴 멱등 키로 찾는다
    @Transactional
    public void cancelPayment(String idempotencyKey) {
        paymentCancelPaymentUseCase.cancel(idempotencyKey);
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

    // PROCESSING 충전 건 결과 확인 (스케줄러). PG 조회를 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음
    // (반영·실패 기록은 PaymentTopupConfirmProcessor에서 건마다 짧은 트랜잭션으로 실행)
    public TopupReconcileResult reconcileProcessingTopups() {
        return paymentReconcileTopupsUseCase.reconcileProcessingTopups(LocalDateTime.now());
    }

    // CANCELING 충전 건 결과 확인 (스케줄러). PG 조회를 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음
    // (확정·되돌림은 PaymentTopupCancelProcessor에서 건마다 짧은 트랜잭션으로 실행)
    public TopupReconcileResult reconcileCancelingTopups() {
        return paymentReconcileCancelingTopupsUseCase.reconcileCancelingTopups(LocalDateTime.now());
    }

    // METHOD_CANCELING 충전 건 결과 확인 (스케줄러). PG 조회·취소를 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음
    // (확정·회차 증가는 PaymentTopupConfirmProcessor에서 건마다 짧은 트랜잭션으로 실행)
    public TopupReconcileResult reconcileMethodCancelingTopups() {
        return paymentReconcileMethodCancelingTopupsUseCase.reconcileMethodCancelingTopups(LocalDateTime.now());
    }

    // 결과가 정해지지 않은 채 오래 남은 충전 건 경고 (스케줄러)
    @Transactional(readOnly = true)
    public long warnStuckTopups() {
        return paymentWarnStuckTopupsUseCase.warnStuckTopups(LocalDateTime.now());
    }

    // 정산 지급. 이 트랜잭션으로 커밋한 뒤 응답한다(정산과 한 트랜잭션이 아니다. 정산은 PROCESSING 선점 후 호출하고 결과를 모르면 findTransfer로 확정)
    @Transactional
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return paymentTransferUseCase.transfer(command);
    }

    // 정산 지급 내역 조회
    @Transactional(readOnly = true)
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        return paymentFindTransferUseCase.findTransfer(idempotencyKey);
    }


//     반품 환불 (반품 승인 이벤트를 받아 부른다).
//     요청 기록 → 환불 → (실패하면) 실패 기록 / 커넥션은 늘 하나만 쓴다
//     일시 오류면 ③ 기록 뒤 예외를 다시 던져 Kafka가 같은 메시지를 다시 주게 만든다(재시도 처리)
    // 구매자 반품 환불 조회
    @Transactional(readOnly = true)
    public RefundDto getRefund(Long buyerId, Long orderId) {
        return paymentGetRefundUseCase.getRefund(buyerId, orderId);
    }

    // 단순 변심 반품: 회수 택배비 결제 + 상품값 환불 (한 트랜잭션)
    @Transactional
    public RefundDto payReturnPickupFee(Long buyerId, Long orderId) {
        return paymentPayReturnPickupFeeUseCase.payPickupFee(buyerId, orderId);
    }

    public void refundReturnedOrder(OrderReturnedEvent event) {
        Long refundId = paymentRequestRefundUseCase.request(event);
        try {
            paymentRefundUseCase.refund(refundId);
        } catch (RuntimeException exception) {
            if (paymentRefundFailureRecorder.record(refundId, exception)) throw exception;
        }
    }

    // 개발용 HELD 홀딩 시드
    @Transactional
    public void createSeedHoldings(Long buyerMemberId, Map<Long, Long> amountByOrderId) {
        paymentCreateSeedHoldingsUseCase.createSeedHoldings(buyerMemberId, amountByOrderId);
    }

    // 개발용 플랫폼(관리자) 지갑 시드 충전 (장바구니 쿠폰 지원금 재원)
    @Transactional
    public void chargeSeedPlatformWallet(long amount) {
        paymentCreateSeedHoldingsUseCase.chargeSeedPlatformWallet(amount);
    }
}
