package com.backend.shared.payment.out;

import java.util.List;
import java.util.Optional;

import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 결제 컨텍스트가 다른 컨텍스트에 제공하는 내부 API.
 * 구현체 {@link com.backend.boundedContext.payment.in.PaymentApiAdapter}
 */
public interface PaymentApi {

    // 잔액과 홀딩 중 금액 조회
    WalletSummaryDto getWallet(Long memberId);

    // 결과가 아직 정해지지 않은 충전 건(READY·PROCESSING)이 있는지. 회원 탈퇴 검사용 (반영 전이라 잔액에 잡히지 않음)
    boolean hasPendingTopups(Long memberId);

    /**
     * 주문 결제: 구매자 지갑을 잠그고 지갑 → 홀딩, 원장 PAYMENT 기록. 호출한 쪽 트랜잭션에 참여한다.
     * 잔액 부족·주문 만료는 예외가 아니라 결과로 반환한다. 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 남긴다.
     * 주문 쪽은 결과가 PAID가 아니면 DomainException(402 INSUFFICIENT_BALANCE 등)을 던진다.
     * 같은 idempotencyKey로 다시 호출하면 처음 결과를 그대로 반환한다.
     */
    PayResultDto pay(PayCommand command);

    /**
     * 주문의 결제 시도 목록 (주문 상세의 결제 내역). 오래된 순, 실패 시도 포함.
     * 내 주문인지는 주문 쪽에서 확인하고 호출한다. 결제 시도가 없으면 빈 목록을 반환한다.
     * 실패 사유는 상태(FAILED_*)로만 알려 주고, 부족 금액은 담지 않는다.
     */
    List<PaymentAttemptDto> getPaymentAttempts(Long orderId);

    /**
     * 정산 지급: 홀딩 → 판매자 지갑(SETTLEMENT), 홀딩 → 관리자 지갑(FEE).
     * 주문별 홀딩의 settled_amount를 늘리고, 다 차면 SETTLED로 바꾼다.
     * 호출한 쪽 트랜잭션에 참여(REQUIRED)하고, 실패하면 DomainException을 던진다.
     * 재시도해도 같은 실패: 404-WALLET_NOT_FOUND, 404-HOLDING_NOT_FOUND, 409-HOLDING_INSUFFICIENT, 409-TRANSFER_AMOUNT_MISMATCH
     * idempotencyKey(SETTLEMENT-{settlementId})가 이미 처리됐으면 기존 결과를 반환한다.
     */
    TransferResultDto transfer(SettlementTransferCommand command);

    /** 정산 지급 여부 조회 (재시도·대사용). 없으면 empty */
    Optional<TransferResultDto> findTransfer(String idempotencyKey);
}
