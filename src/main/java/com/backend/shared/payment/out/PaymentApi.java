package com.backend.shared.payment.out;

import java.util.Optional;

import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
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

    /**
     * 주문 결제: 구매자 지갑을 잠그고 지갑 → 홀딩, 원장 PAYMENT 기록. 호출한 쪽 트랜잭션에 참여한다.
     * 잔액 부족·주문 만료는 예외가 아니라 결과로 반환한다. 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 남긴다.
     * 주문 쪽은 결과가 PAID가 아니면 DomainException(402 INSUFFICIENT_BALANCE 등)을 던진다.
     * 같은 idempotencyKey로 다시 호출하면 처음 결과를 그대로 반환한다.
     */
    PayResultDto pay(PayCommand command);

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
