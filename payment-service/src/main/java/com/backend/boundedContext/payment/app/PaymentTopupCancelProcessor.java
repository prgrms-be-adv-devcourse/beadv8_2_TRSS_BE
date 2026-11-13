package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.TopupCancelDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 충전 취소의 DB 트랜잭션 구간 (PaymentTopupConfirmProcessor와 같은 이유로 별도 빈)
 * Tx1(취소 시작) → PG 취소 호출(트랜잭션 밖) → Tx2(성공 반영 또는 되돌림)
 *
 * 잠금 순서: 지갑 → 충전 건
 * 결제(오래된 충전 건부터 차감)·결제 취소(충전 건 복원)도 지갑을 먼저 잠그므로, 같은 회원의 작업은 지갑에서 순서대로 처리되고 교착이 생기지 않음
 */
@Component
@RequiredArgsConstructor
public class PaymentTopupCancelProcessor {

    private final TopupRepository topupRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    /**
     * Tx1: PG 취소 호출 전 남은 금액 전부를 취소 중으로 옮기고 지갑 잔액에서 먼저 뺌
     * 1. 지갑 → 충전 건 잠금 조회 (조건에 memberId 포함, 남의 충전 건이면 404)
     * 2. DONE이 아니거나 남은 금액이 없으면 409 TOPUP_NOT_CANCELABLE
     * 3. DONE → CANCELING, 남은 금액 → 취소 중 금액
     * 4. 원장 CHARGE_CANCEL 기록(잔액 차감). 취소 중인 금액이 PG 응답을 기다리는 동안 결제에 쓰이지 않게 함
     */
    @Transactional
    public TopupCancelStartResult start(Long memberId, Long topupId) {
        Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
        Topup topup = topupRepository.findByIdAndMemberIdForUpdate(topupId, memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));

        topup.startCancel();
        String idempotencyKey = topup.cancelIdempotencyKey();
        walletTransactionRepository.save(WalletTransaction.record(wallet, WalletTransactionType.CHARGE_CANCEL, topup.getCancelingAmount(), topup.getId(), idempotencyKey));

        return new TopupCancelStartResult(topup.getId(), topup.getTossOrderId(), topup.getPgProvider(), topup.getPaymentKey(), topup.getCancelingAmount(), topup.getCancelAttempts(), idempotencyKey);
    }

    /**
     * Tx2 (취소 성공): 취소 중 금액을 취소된 금액으로 확정. 잔액은 Tx1에서 이미 뺐으므로 그대로
     * 이 회차가 CANCELING이 아니면 이미 반영된 것으로 보고 현재 상태만 반환 (취소 응답과 스케줄러가 동시에 반영해도 한 번만 반영됨)
     * cancelAmount는 응답에 담을 이번 취소 금액 (이미 반영됐으면 취소 중 금액이 0이라 호출한 쪽에서 받음)
     */
    @Transactional
    public TopupCancelDto complete(Long topupId, int attempt, long cancelAmount) {
        Locked locked = lock(topupId);
        Topup topup = locked.topup();

        if (topup.isCancelingAttempt(attempt)) {
            topup.completeCancel(LocalDateTime.now());
        }
        return TopupCancelDto.of(topup, cancelAmount, locked.wallet().getBalance());
    }

    /**
     * Tx2 (취소 확정 실패): 취소 중 금액을 남은 금액으로 되돌리고, Tx1에서 뺀 잔액을 원장 CHARGE_CANCEL_RESTORE로 다시 넣음
     * 이 회차가 CANCELING이 아니면 이미 반영된 것으로 보고 아무것도 하지 않음 (그사이 시작된 다음 회차를 되돌리지 않게 회차까지 확인)
     */
    @Transactional
    public void fail(Long topupId, int attempt) {
        Locked locked = lock(topupId);
        Topup topup = locked.topup();

        if (!topup.isCancelingAttempt(attempt)) {
            return;
        }
        // failCancel()이 취소 중 금액을 0으로 만들기 전에 되돌릴 금액·키를 읽음
        walletTransactionRepository.save(WalletTransaction.record(locked.wallet(), WalletTransactionType.CHARGE_CANCEL_RESTORE, topup.getCancelingAmount(), topup.getId(), topup.cancelRestoreIdempotencyKey()));
        topup.failCancel();
    }

    /**
     * 취소 결과 확인 대상: 기준 시각 이전부터 CANCELING으로 남은 충전 건 (오래된 순, 최대 limit건)
     * PG 조회는 트랜잭션 밖에서 하므로 엔티티 대신 판단에 필요한 값만 넘김
     */
    @Transactional(readOnly = true)
    public List<TopupCancelReconcileTarget> findReconcileTargets(LocalDateTime cutoff, int limit) {
        return topupRepository.findStale(TopupStatus.CANCELING, cutoff, PageRequest.of(0, limit)).stream()
                .map(topup -> new TopupCancelReconcileTarget(topup.getId(), topup.getPaymentKey(), topup.getPgProvider(), topup.getCancelAttempts(), topup.getCanceledAmount(), topup.getCancelingAmount()))
                .toList();
    }

    // 지갑 → 충전 건 순서로 잠금. 지갑을 찾으려면 회원 ID가 필요하므로, 충전 건은 잠그지 않고 회원 ID만 먼저 읽음
    private Locked lock(Long topupId) {
        Long memberId = topupRepository.findMemberIdById(topupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));
        Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
        Topup topup = topupRepository.findByIdForUpdate(topupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));
        return new Locked(wallet, topup);
    }

    private record Locked(Wallet wallet, Topup topup) {}
}
