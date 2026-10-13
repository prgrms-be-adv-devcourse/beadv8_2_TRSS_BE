package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 충전 승인의 DB 트랜잭션 구간
 * 전체 승인 흐름은 Facade에서 조율하되, Facade 전체를 하나의 트랜잭션으로 묶지는 않음
 * Tx1 → 토스 호출(트랜잭션 밖) → Tx2 중 DB 작업만 각각 짧은 트랜잭션으로 실행하기 위해 별도 빈으로 분리
 * (같은 클래스 내부 호출은 Spring 프록시를 거치지 않아 메서드의 @Transactional이 적용되지 않음)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTopupConfirmProcessor {

    private static final String CARD_METHOD = "카드";

    private final TopupRepository topupRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    /**
     * Tx1: 토스 승인 호출 전 충전 건을 PROCESSING으로 선점
     * 1. 충전 건 행 잠금 조회 (조건에 memberId 포함, 남의 충전 건이면 404)
     * 2. READY가 아니면 409 TOPUP_ALREADY_PROCESSED (상태는 그대로)
     * 3. 이미 다른 충전 건에 쓰인 결제키면 409 TOPUP_ALREADY_PROCESSED
     * 4. 저장 금액 ≠ 요청 금액이면 FAILED_AMOUNT_MISMATCH로 기록하고 결과로 반환 (예외를 던지지 않아 기록이 커밋됨)
     * 5. READY → PROCESSING, 결제키 저장
     */
    @Transactional
    public TopupConfirmStartResult start(Long memberId, String orderId, String paymentKey, long amount) {
        Topup topup = topupRepository.findByTossOrderIdAndMemberIdForUpdate(orderId, memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));

        if (topup.getStatus() != TopupStatus.READY || topupRepository.existsByPaymentKey(paymentKey)) {
            throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        }

        if (topup.getAmount() != amount) {
            topup.fail(TopupStatus.FAILED_AMOUNT_MISMATCH, "충전 준비 금액과 승인 요청 금액이 다릅니다. (준비 " + topup.getAmount() + "원, 요청 " + amount + "원)");
            return new TopupConfirmStartResult(topup.getId(), TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH);
        }

        topup.markProcessing(paymentKey);
        return new TopupConfirmStartResult(topup.getId(), TopupConfirmStartResult.Outcome.STARTED);
    }

    /**
     * Tx2 (승인 성공): 토스가 승인한 충전 건을 예치금에 반영. 대사 스케줄러도 같은 메서드로 반영
     * 1. 충전 건 행 잠금 조회. PROCESSING이 아니면 이미 반영된 것으로 보고 현재 상태만 반환
     *    (승인 응답과 스케줄러가 같은 충전 건을 동시에 반영하려 해도 한 번만 반영됨)
     * 2. 지갑 잠금 조회 → 원장 CHARGE 기록(잔액 증가) → 충전 건 DONE
     * 3. 결제 수단이 카드가 아니면 경고 로그 (토스 취소가 보류되어 되돌릴 수 없으므로 반영은 그대로 진행)
     * 잠금 순서: 충전 건 → 지갑
     */
    @Transactional
    public TopupConfirmDto complete(Long topupId, TossPaymentResponse toss) {
        if (!toss.isApproved()) {
            throw new IllegalArgumentException("승인 완료(DONE)된 토스 결제만 반영할 수 있습니다: " + toss.status());
        }

        Topup topup = topupRepository.findByIdForUpdate(topupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));
        Wallet wallet = walletRepository.findByMemberIdForUpdate(topup.getMemberId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        if (topup.getStatus() != TopupStatus.PROCESSING) {
            return TopupConfirmDto.of(topup, wallet.getBalance());
        }

        if (toss.totalAmount() != topup.getAmount()) {
            log.error("토스 승인 금액이 충전 금액과 다릅니다. topupId={}, 충전 {}원, 토스 {}원",
                    topupId, topup.getAmount(), toss.totalAmount());
        }
        if (!CARD_METHOD.equals(toss.method())) {
            log.warn("카드가 아닌 결제 수단으로 충전이 승인됐습니다. topupId={}, method={}", topupId, toss.method());
        }

        walletTransactionRepository.save(WalletTransaction.record(wallet, WalletTransactionType.CHARGE, topup.getAmount(), topup.getId(), topup.chargeIdempotencyKey()));
        topup.approve(toss.approvedAtKst() != null ? toss.approvedAtKst() : LocalDateTime.now());

        return TopupConfirmDto.of(topup, wallet.getBalance());
    }

    /**
     * Tx2 (승인 실패 확정): PROCESSING 충전 건을 실패로 기록
     * PROCESSING이 아니면 이미 결과가 반영된 것으로 보고 아무것도 하지 않음
     */
    @Transactional
    public void fail(Long topupId, TopupStatus failStatus, String reason) {
        Topup topup = topupRepository.findByIdForUpdate(topupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));

        if (topup.getStatus() != TopupStatus.PROCESSING) {
            return;
        }
        topup.fail(failStatus, reason);
    }
}
