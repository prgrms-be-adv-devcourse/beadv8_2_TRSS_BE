package com.backend.boundedContext.payment.app;

import static com.backend.boundedContext.payment.out.WalletTransactionSpecifications.createdFrom;
import static com.backend.boundedContext.payment.out.WalletTransactionSpecifications.createdTo;
import static com.backend.boundedContext.payment.out.WalletTransactionSpecifications.typeEquals;
import static com.backend.boundedContext.payment.out.WalletTransactionSpecifications.walletEquals;

import java.time.LocalDate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.WalletTransactionDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetWalletTransactionsUseCase {

    // 거래 최신순 (같은 시각이면 ID 역순). 요청의 sort는 무시하고 인덱스(wallet_id, created_at)에 맞는 정렬로 고정
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    // 내 예치금 거래 내역
    public PageResponse<WalletTransactionDto> getTransactions(Long memberId, WalletTransactionType type, LocalDate from, LocalDate to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(PaymentErrorCode.WALLET_TRANSACTION_PERIOD_INVALID);
        }

        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Specification<WalletTransaction> spec = Specification.allOf(
                walletEquals(wallet.getId()),
                typeEquals(type),
                createdFrom(from),
                createdTo(to)
        );
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        return PageResponse.from(walletTransactionRepository.findAll(spec, latestFirst), WalletTransactionDto::from);
    }
}
