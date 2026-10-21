package com.backend.boundedContext.payment.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.AdminPaymentDto;
import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetAdminPaymentsUseCase {

    // 결제 시도 최신순 (같은 시각이면 ID 역순). 요청의 sort는 무시
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PaymentAdminSearchResolver searchResolver;
    private final PaymentRepository paymentRepository;
    private final WalletHoldingRepository walletHoldingRepository;
    private final MemberApi memberApi;

    /**
     * 관리자 결제 조회 결제 탭
     * 회원 이메일·보관 금액 상태는 페이지에 나온 결제 건만 모아 한 번씩 조회 (행마다 조회하지 않음)
     */
    public PageResponse<AdminPaymentDto> getPayments(String keyword, LocalDate from, LocalDate to, Pageable pageable) {
        AdminPaymentSearch search = searchResolver.resolve(keyword, from, to);
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Page<Payment> payments = search.noResult()
                ? Page.empty(latestFirst)
                : paymentRepository.findAll(search.paymentSpec(), latestFirst);

        Map<Long, String> emailByMemberId = getEmails(payments.getContent());
        Map<Long, WalletHoldingStatus> holdingStatusByPaymentId = getHoldingStatuses(payments.getContent());

        return PageResponse.from(
                payments, payment -> AdminPaymentDto.of(
                    payment,
                    emailByMemberId.get(payment.getMemberId()),
                    holdingStatusByPaymentId.get(payment.getId())
                )
        );
    }

    private Map<Long, String> getEmails(List<Payment> payments) {
        List<Long> memberIds = payments.stream().map(Payment::getMemberId).distinct().toList();
        if (memberIds.isEmpty()) return Map.of();
        return memberApi.getMembers(memberIds).stream()
                .collect(Collectors.toMap(MemberDto::id, MemberDto::email));
    }

    // 홀딩이 있는 건(결제 완료·환불)만 결과에 들어오고, 실패 건은 빠짐
    private Map<Long, WalletHoldingStatus> getHoldingStatuses(List<Payment> payments) {
        List<Long> paymentIds = payments.stream().map(Payment::getId).toList();
        if (paymentIds.isEmpty()) return Map.of();
        return walletHoldingRepository.findAllByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.toMap(holding -> holding.getPayment().getId(), WalletHolding::getStatus));
    }
}
