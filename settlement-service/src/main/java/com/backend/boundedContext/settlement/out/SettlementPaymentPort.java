package com.backend.boundedContext.settlement.out;

import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;

import java.util.Optional;

/**
 * 정산 컨텍스트가 쓰는 결제 기능만 담은 Port
 * 지금은 같은 앱의 PaymentApi를 부르고, 정산 모듈이 분리되면 결제 내부 API(/internal/v1/payments/transfers)를 부르는 HTTP 클라이언트로 바꿈
 */
public interface SettlementPaymentPort {

    // 정산 지급: 홀딩 차감·판매자/관리자 지갑 입금·원장 기록. 같은 멱등 키면 기존 결과를 돌려줌
    TransferResultDto transfer(SettlementTransferCommand command);

    /**
     * 정산서 멱등 키(SETTLEMENT-{id})로 지급 원장을 다시 읽는다. 지급 기록이 없으면 empty
     * 결제가 저장된 값이 아니라 원장 행(-PAYOUT, -FEE)을 다시 읽어 만들어 주므로 원장 재대조에 쓴다
     */
    Optional<TransferResultDto> findTransfer(String idempotencyKey);
}
