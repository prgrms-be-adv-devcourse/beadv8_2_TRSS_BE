package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.member.dto.MemberDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 실패 거래 화면(A7)의 결제, 환불 행. 공통 형식(AdminFailureItem)으로 돌려준다
 *
 * PAYMENT_FAILED: 결제 실패 상태(잔액 부족, 주문 만료, 시스템 오류)인 결제 건
 * REFUND_FAILED : 실패 상태(FAILED_*)인 반품 환불. 시스템 오류이고 재시도 횟수가 남았으면 자동 재시도 대기, 그 밖에는 사람 확인
 * occurredAt은 지금 상태가 된 시각(updatedAt). 기간은 그 날짜로 거른다(from, to 포함, 한국 시간)
 * type이 없으면 두 유형을 합쳐 최신순으로 한 페이지를 만든다. 합친 목록의 앞 offset + size건이 정확하려면 두 유형 모두
 * 앞에서 offset + size건씩 읽어야 해서(한 유형이 그 구간을 다 차지할 수 있음) 읽는 건수에 상한을 두지 않는다.
 * 뒤쪽 페이지일수록 많이 읽으므로, 실패 거래가 많이 쌓여 느려지면 DB에서 ID와 시각만 UNION ALL로 합쳐
 * 페이지를 나눈 뒤 그 페이지의 엔티티만 읽는 방식으로 바꾼다
 * 충전 확인 지연(TOPUP_DELAYED)은 충전 쪽(/api/v1/admin/topups/failures)이 돌려준다
 */
@Component
@RequiredArgsConstructor
public class PaymentGetFailuresUseCase {

    static final Set<PaymentStatus> PAYMENT_FAILED_STATUSES = EnumSet.of(PaymentStatus.FAILED_INSUFFICIENT_BALANCE,
            PaymentStatus.FAILED_ORDER_EXPIRED, PaymentStatus.FAILED_SYSTEM_ERROR);
    static final Set<PaymentRefundStatus> REFUND_FAILED_STATUSES = Arrays.stream(PaymentRefundStatus.values())
            .filter(PaymentRefundStatus::isFailure)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(PaymentRefundStatus.class)));

    private static final LocalDateTime EARLIEST = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final PaymentMemberPort memberPort;

    public PageResponse<AdminFailureItem> getFailures(AdminFailureType type, LocalDate from, LocalDate to,
                                                      Pageable pageable) {
        LocalDateTime fromAt = from == null ? EARLIEST : from.atStartOfDay();
        LocalDateTime toAt = to == null ? LATEST : to.plusDays(1).atStartOfDay();
        Pageable request = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_FIRST);

        if (type == AdminFailureType.PAYMENT_FAILED) {
            return toResponse(paymentRepository.findFailures(PAYMENT_FAILED_STATUSES, fromAt, toAt, request));
        }
        if (type == AdminFailureType.REFUND_FAILED) {
            return toResponse(paymentRefundRepository.findFailures(REFUND_FAILED_STATUSES, fromAt, toAt, request));
        }
        if (type != null) {
            // 이 API가 다루지 않는 유형(충전, 정산, 배송)은 빈 목록
            return PageResponse.from(Page.empty(pageable));
        }
        return merged(fromAt, toAt, pageable);
    }

    /**
     * 두 유형을 각각 앞에서부터 offset + size건씩(그 유형의 건수까지만) 읽어 최신순으로 합친 뒤 요청 페이지만 자른다
     * 건수를 먼저 세어, 요청 페이지가 전체 건수를 넘으면 읽지 않고 빈 페이지를 준다
     */
    private PageResponse<AdminFailureItem> merged(LocalDateTime fromAt, LocalDateTime toAt, Pageable pageable) {
        long paymentTotal = paymentRepository.countFailures(PAYMENT_FAILED_STATUSES, fromAt, toAt);
        long refundTotal = paymentRefundRepository.countFailures(REFUND_FAILED_STATUSES, fromAt, toAt);
        long total = paymentTotal + refundTotal;
        if (pageable.getOffset() >= total) {
            return toResponse(new PageImpl<>(List.of(), pageable, total));
        }
        long need = pageable.getOffset() + pageable.getPageSize();
        List<Object> rows = new ArrayList<>(head(need, paymentTotal,
                limit -> paymentRepository.findFailures(PAYMENT_FAILED_STATUSES, fromAt, toAt, limit)));
        rows.addAll(head(need, refundTotal,
                limit -> paymentRefundRepository.findFailures(REFUND_FAILED_STATUSES, fromAt, toAt, limit)));
        rows.sort(Comparator.comparing(PaymentGetFailuresUseCase::occurredAtOf).reversed()
                .thenComparing(PaymentGetFailuresUseCase::idOf, Comparator.reverseOrder()));
        // 건수와 목록은 다른 쿼리라, 그사이 실패가 아니게 된 건(예: 환불 재시도 성공)이 있으면 목록이 건수보다 적을 수 있다
        int start = (int) pageable.getOffset();
        if (start >= rows.size()) {
            return toResponse(new PageImpl<>(List.of(), pageable, total));
        }
        int end = Math.min(start + pageable.getPageSize(), rows.size());
        return toResponse(new PageImpl<>(rows.subList(start, end), pageable, total));
    }

    /** 한 유형을 최신순으로 앞에서 min(need, 건수)건 읽는다. 건수가 0이면 읽지 않는다 */
    private static <T> List<T> head(long need, long total, Function<Pageable, Page<T>> query) {
        int limit = (int) Math.min(need, total);
        if (limit == 0) return List.of();
        return query.apply(PageRequest.of(0, limit, LATEST_FIRST)).getContent();
    }

    /**
     * 페이지에 나온 행의 회원 ID와 이메일을 한 번에 모아(행마다 조회하지 않음) 공통 형식으로 바꾼다
     * 환불은 회원 ID가 없어 환불한 결제(paymentId)에서, 결제를 못 찾은 환불은 주문의 결제에서 찾는다
     */
    private PageResponse<AdminFailureItem> toResponse(Page<?> page) {
        Map<Long, Long> memberIdByRefundId = refundMemberIds(page.getContent().stream()
                .filter(PaymentRefund.class::isInstance).map(PaymentRefund.class::cast).toList());
        List<Long> memberIds = page.getContent().stream()
                .map(row -> row instanceof Payment p ? p.getMemberId() : memberIdByRefundId.get(((PaymentRefund) row).getId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> emails = emailsOf(memberIds);
        return PageResponse.from(page, row -> {
            if (row instanceof Payment p) return toItem(p, emails.get(p.getMemberId()));
            PaymentRefund refund = (PaymentRefund) row;
            Long memberId = memberIdByRefundId.get(refund.getId());
            return toItem(refund, memberId, memberId == null ? null : emails.get(memberId));
        });
    }

    private Map<Long, Long> refundMemberIds(List<PaymentRefund> refunds) {
        if (refunds.isEmpty()) return Map.of();
        List<Long> paymentIds = refunds.stream().map(PaymentRefund::getPaymentId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> memberIdByPaymentId = paymentIds.isEmpty() ? Map.of()
                : paymentRepository.findAllById(paymentIds).stream()
                        .collect(Collectors.toMap(Payment::getId, Payment::getMemberId, (a, b) -> a));
        Map<Long, Long> result = new HashMap<>();
        for (PaymentRefund refund : refunds) {
            Long memberId = refund.getPaymentId() == null ? null : memberIdByPaymentId.get(refund.getPaymentId());
            if (memberId == null) {
                // 결제를 찾지 못한 채 실패한 환불(예: 결제 완료 건 없음): 주문의 결제 시도에서 구매자를 찾는다(드문 경우)
                memberId = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(refund.getOrderId()).stream()
                        .map(Payment::getMemberId).findFirst().orElse(null);
            }
            if (memberId != null) result.put(refund.getId(), memberId);
        }
        return result;
    }

    private Map<Long, String> emailsOf(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return Map.of();
        return memberPort.getMembers(memberIds).stream()
                .filter(member -> member.email() != null)
                .collect(Collectors.toMap(MemberDto::id, MemberDto::email, (a, b) -> a));
    }

    static AdminFailureItem toItem(Payment payment, String email) {
        boolean expired = payment.getStatus() == PaymentStatus.FAILED_ORDER_EXPIRED;
        return new AdminFailureItem(
                AdminFailureType.PAYMENT_FAILED,
                payment.getUpdatedAt(),
                AdminFailureTargetType.MEMBER,
                payment.getMemberId(),
                AdminFailureItem.maskEmail(email),
                payment.getAmount(),
                payment.getStatus().name(),
                paymentReasonLabel(payment.getStatus()),
                expired ? "ORDER_EXPIRED" : "BUYER_CAN_RETRY",
                expired ? "주문 만료로 다시 주문해야 해요" : "구매자가 다시 결제할 수 있어요",
                payment.getId(),
                payment.getOrderId());
    }

    static AdminFailureItem toItem(PaymentRefund refund, Long memberId, String email) {
        boolean autoRetry = refund.isProcessable();
        return new AdminFailureItem(
                AdminFailureType.REFUND_FAILED,
                refund.getUpdatedAt(),
                AdminFailureTargetType.MEMBER,
                memberId,
                AdminFailureItem.maskEmail(email),
                refund.getAmount() > 0 ? refund.getAmount() : refund.getRequestedAmount(),
                refund.getStatus().name(),
                refundReasonLabel(refund.getStatus()),
                autoRetry ? "AUTO_RETRY" : "CHECK_REQUIRED",
                autoRetry ? "자동 재시도 대기" : "수동 확인 필요",
                refund.getId(),
                refund.getOrderId());
    }

    static String paymentReasonLabel(PaymentStatus status) {
        return switch (status) {
            case FAILED_INSUFFICIENT_BALANCE -> "잔액 부족";
            case FAILED_ORDER_EXPIRED -> "주문 만료";
            case FAILED_SYSTEM_ERROR -> "시스템 오류";
            default -> status.name();
        };
    }

    static String refundReasonLabel(PaymentRefundStatus status) {
        return switch (status) {
            case FAILED_AMOUNT_MISMATCH -> "환불 금액이 결제 금액과 다름";
            case FAILED_HOLDING_SETTLED -> "이미 정산된 주문";
            case FAILED_PAYMENT_NOT_PAID -> "결제 완료 건 없음";
            case FAILED_PLATFORM_BALANCE -> "플랫폼 잔액 부족";
            case FAILED_SYSTEM_ERROR -> "환불 처리 오류";
            default -> status.name();
        };
    }

    private static LocalDateTime occurredAtOf(Object row) {
        return row instanceof Payment p ? p.getUpdatedAt() : ((PaymentRefund) row).getUpdatedAt();
    }

    private static Long idOf(Object row) {
        return row instanceof Payment p ? p.getId() : ((PaymentRefund) row).getId();
    }
}
