package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.SettledOrderAmountDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentTransferUseCase {

    private final PaymentPolicy paymentPolicy;
    private final PaymentFindTransferUseCase paymentFindTransferUseCase;
    private final WalletRepository walletRepository;
    private final WalletHoldingRepository walletHoldingRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    /**
     * 정산 지급: 홀딩 → 판매자 지갑(SETTLEMENT), 홀딩 → 관리자 지갑(FEE)
     * 1. 같은 멱등 키로 이미 지급했으면 기존 결과를 반환 (금액 비교는 정산 쪽에서 함)
     * 2. 주문별 금액 합계 = 지급액 + 수수료인지 확인
     * 3. 홀딩을 주문 ID 오름차순으로 잠그고, 주문별 금액만큼 정산 누적 (다 차면 SETTLED)
     * 4. 판매자·관리자 지갑을 회원 ID 오름차순으로 잠그고 입금, 원장 기록
     * 실패는 모두 DomainException으로 던지고, 호출한 쪽 트랜잭션과 함께 롤백된다
     */
    public TransferResultDto transfer(SettlementTransferCommand command) {
        Optional<TransferResultDto> existing = paymentFindTransferUseCase.findTransfer(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        Map<Long, Long> amountByOrderId = sumByOrderId(command.orders());
        long orderTotal = amountByOrderId.values().stream().mapToLong(Long::longValue).sum();
        if (command.payoutAmount() < 0
                || command.feeAmount() < 0
                || orderTotal != command.payoutAmount() + command.feeAmount()) {
            throw new DomainException(PaymentErrorCode.TRANSFER_AMOUNT_MISMATCH);
        }

        LocalDateTime now = LocalDateTime.now();
        settleHoldings(amountByOrderId, now);

        Map<Long, Wallet> wallets = lockWallets(command.sellerMemberId(), paymentPolicy.adminMemberId());
        WalletTransaction payout = deposit(
                wallets.get(command.sellerMemberId()),
                WalletTransactionType.SETTLEMENT,
                command.payoutAmount(),
                command.settlementId(),
                command.idempotencyKey() + PaymentFindTransferUseCase.PAYOUT_KEY_SUFFIX
        );
        WalletTransaction fee = deposit(wallets.get(
                paymentPolicy.adminMemberId()),
                WalletTransactionType.FEE,
                command.feeAmount(),
                command.settlementId(),
                command.idempotencyKey() + PaymentFindTransferUseCase.FEE_KEY_SUFFIX
        );

        WalletTransaction first = payout != null ? payout : fee;
        return new TransferResultDto(
                command.idempotencyKey(),
                payout != null ? payout.getId() : null,
                command.payoutAmount(),
                command.feeAmount(),
                first.getCreatedAt());
    }

    // 같은 주문이 여러 번 들어와도 합쳐서 처리.
    private Map<Long, Long> sumByOrderId(List<SettledOrderAmountDto> orders) {
        if (orders == null || orders.isEmpty()) {
            throw new DomainException(PaymentErrorCode.TRANSFER_AMOUNT_MISMATCH);
        }
        for (SettledOrderAmountDto order : orders) {
            if (order.amount() <= 0) throw new DomainException(PaymentErrorCode.TRANSFER_AMOUNT_MISMATCH);
        }
        return orders.stream().collect(Collectors.toMap(
                SettledOrderAmountDto::orderId, SettledOrderAmountDto::amount, Long::sum, TreeMap::new));
    }

    private void settleHoldings(Map<Long, Long> amountByOrderId, LocalDateTime now) {
        Map<Long, WalletHolding> holdings = walletHoldingRepository.findAllByOrderIdInForUpdate(amountByOrderId.keySet())
                .stream()
                .collect(Collectors.toMap(WalletHolding::getOrderId, Function.identity()));

        amountByOrderId.forEach((orderId, amount) -> {
            WalletHolding holding = holdings.get(orderId);
            if (holding == null || holding.getStatus() != WalletHoldingStatus.HELD) {
                throw new DomainException(PaymentErrorCode.HOLDING_NOT_FOUND);
            }
            holding.settle(amount, now);  // 남은 금액보다 많으면 409 HOLDING_INSUFFICIENT
        });
    }

    // 데드락을 막기 위해 회원 ID 오름차순으로 잠근다. 판매자와 관리자가 같은 회원이면 한 번만 잠근다
    private Map<Long, Wallet> lockWallets(Long sellerMemberId, Long adminMemberId) {
        Map<Long, Wallet> wallets = new TreeMap<>();
        for (Long memberId : new TreeSet<>(List.of(sellerMemberId, adminMemberId))) {
            Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                    .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
            wallets.put(memberId, wallet);
        }
        return wallets;
    }

    // 0원이면 입금·원장 기록을 하지 않는다 (예: 수수료가 0원인 소액 정산)
    private WalletTransaction deposit(Wallet wallet, WalletTransactionType type, long amount,
                                      Long settlementId, String idempotencyKey) {
        if (amount == 0) {
            return null;
        }
        return walletTransactionRepository.save(WalletTransaction.record(wallet, type, amount, settlementId, idempotencyKey));
    }
}
