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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 관리자 실패 거래(A7)의 결제, 환불 행: 공통 형식 변환, 기간, 두 유형 합치기
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentGetFailuresUseCaseTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentRefundRepository paymentRefundRepository;
    @Mock PaymentMemberPort memberPort;
    @InjectMocks PaymentGetFailuresUseCase useCase;

    @BeforeEach
    void setUp() {
        given(memberPort.getMembers(anyCollection())).willReturn(List.of(
                new MemberDto(3L, "buyer@palette.test", "구매자", null, null)));
    }

    @Test
    @DisplayName("결제 실패는 회원 대상, 결제 금액, 원인 문구, 구매자 재결제 안내로 바꾸고, 기간은 from 00:00부터 to 다음 날 00:00 전까지다")
    void paymentFailed() {
        Payment payment = payment(12L, 3L, 142L, 37_800L, PaymentStatus.FAILED_SYSTEM_ERROR, LocalDateTime.of(2026, 11, 9, 14, 20));
        given(paymentRepository.findFailures(anyCollection(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(payment), PageRequest.of(0, 20), 1));

        PageResponse<AdminFailureItem> page = useCase.getFailures(AdminFailureType.PAYMENT_FAILED,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9), PageRequest.of(0, 20));

        verify(paymentRepository).findFailures(eq(PaymentGetFailuresUseCase.PAYMENT_FAILED_STATUSES),
                eq(LocalDateTime.of(2026, 11, 1, 0, 0)), eq(LocalDateTime.of(2026, 11, 10, 0, 0)), any());
        verify(paymentRefundRepository, never()).findFailures(anyCollection(), any(), any(), any());
        AdminFailureItem item = page.items().getFirst();
        assertThat(item.type()).isEqualTo(AdminFailureType.PAYMENT_FAILED);
        assertThat(item.occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 14, 20));
        assertThat(item.targetType()).isEqualTo(AdminFailureTargetType.MEMBER);
        assertThat(item.targetId()).isEqualTo(3L);
        assertThat(item.targetLabel()).isEqualTo("bu***@palette.test");
        assertThat(item.amount()).isEqualTo(37_800L);
        assertThat(item.reasonLabel()).isEqualTo("시스템 오류");
        assertThat(item.handlingCode()).isEqualTo("BUYER_CAN_RETRY");
        assertThat(item.refId()).isEqualTo(12L);
        assertThat(item.orderId()).isEqualTo(142L);
    }

    @Test
    @DisplayName("주문 만료 결제는 다시 주문해야 한다고 안내한다")
    void paymentExpired() {
        Payment payment = payment(13L, 3L, 143L, 10_000L, PaymentStatus.FAILED_ORDER_EXPIRED, LocalDateTime.now());

        AdminFailureItem item = PaymentGetFailuresUseCase.toItem(payment, "buyer@palette.test");

        assertThat(item.reasonLabel()).isEqualTo("주문 만료");
        assertThat(item.handlingCode()).isEqualTo("ORDER_EXPIRED");
    }

    @Test
    @DisplayName("환불 실패는 환불한 결제의 구매자를 대상으로, 재시도가 남은 시스템 오류는 자동 재시도 대기로 안내한다")
    void refundFailedAutoRetry() {
        PaymentRefund refund = refund(21L, 142L, 12L, 41_000L, PaymentRefundStatus.FAILED_SYSTEM_ERROR, 1,
                LocalDateTime.of(2026, 11, 8, 18, 44));
        given(paymentRefundRepository.findFailures(anyCollection(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(refund), PageRequest.of(0, 20), 1));
        given(paymentRepository.findAllById(anyCollection()))
                .willReturn(List.of(payment(12L, 3L, 142L, 41_000L, PaymentStatus.PAID, LocalDateTime.now())));

        PageResponse<AdminFailureItem> page = useCase.getFailures(AdminFailureType.REFUND_FAILED, null, null,
                PageRequest.of(0, 20));

        AdminFailureItem item = page.items().getFirst();
        assertThat(item.type()).isEqualTo(AdminFailureType.REFUND_FAILED);
        assertThat(item.occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 8, 18, 44));
        assertThat(item.targetId()).isEqualTo(3L);
        assertThat(item.targetLabel()).isEqualTo("bu***@palette.test");
        assertThat(item.amount()).isEqualTo(41_000L);
        assertThat(item.reasonLabel()).isEqualTo("환불 처리 오류");
        assertThat(item.handlingCode()).isEqualTo("AUTO_RETRY");
        assertThat(item.handlingLabel()).isEqualTo("자동 재시도 대기");
        assertThat(item.refId()).isEqualTo(21L);
        assertThat(item.orderId()).isEqualTo(142L);
    }

    @Test
    @DisplayName("재시도를 다 쓴 시스템 오류나 사유가 정해진 실패는 수동 확인 필요다. 결제를 못 찾은 환불은 주문의 결제에서 구매자를 찾는다")
    void refundFailedManual() {
        PaymentRefund exhausted = refund(22L, 150L, null, 5_000L, PaymentRefundStatus.FAILED_SYSTEM_ERROR,
                PaymentRefund.MAX_SYSTEM_ERROR_RETRIES, LocalDateTime.now());
        given(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(anyLong()))
                .willReturn(List.of(payment(30L, 3L, 150L, 5_000L, PaymentStatus.CANCELED, LocalDateTime.now())));

        AdminFailureItem byStatus = PaymentGetFailuresUseCase.toItem(
                refund(23L, 151L, 12L, 9_000L, PaymentRefundStatus.FAILED_HOLDING_SETTLED, 0, LocalDateTime.now()),
                3L, "buyer@palette.test");
        assertThat(byStatus.handlingCode()).isEqualTo("CHECK_REQUIRED");
        assertThat(byStatus.reasonLabel()).isEqualTo("이미 정산된 주문");

        given(paymentRefundRepository.findFailures(anyCollection(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(exhausted), PageRequest.of(0, 20), 1));
        AdminFailureItem item = useCase.getFailures(AdminFailureType.REFUND_FAILED, null, null, PageRequest.of(0, 20))
                .items().getFirst();
        assertThat(item.handlingCode()).isEqualTo("CHECK_REQUIRED");
        assertThat(item.targetId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("type이 없으면 결제 실패와 환불 실패를 합쳐 최신순으로 한 페이지를 만든다")
    void mergedLatestFirst() {
        Payment older = payment(12L, 3L, 142L, 37_800L, PaymentStatus.FAILED_SYSTEM_ERROR, LocalDateTime.of(2026, 11, 9, 10, 0));
        PaymentRefund newer = refund(21L, 143L, 13L, 41_000L, PaymentRefundStatus.FAILED_PLATFORM_BALANCE, 0,
                LocalDateTime.of(2026, 11, 9, 11, 0));
        stubFailures(List.of(older), List.of(newer));
        given(paymentRepository.findAllById(anyCollection()))
                .willReturn(List.of(payment(13L, 3L, 143L, 41_000L, PaymentStatus.PAID, LocalDateTime.now())));

        PageResponse<AdminFailureItem> page = useCase.getFailures(null, null, null, PageRequest.of(0, 20));

        assertThat(page.items()).extracting(AdminFailureItem::type)
                .containsExactly(AdminFailureType.REFUND_FAILED, AdminFailureType.PAYMENT_FAILED);
        assertThat(page.totalElements()).isEqualTo(2L);
    }

    @Test
    @DisplayName("합쳐 볼 때 1,000건을 넘는 뒤쪽 페이지도 정확하다: 두 유형을 offset + size건씩 읽어 합친 뒤 자른다")
    void mergedDeepPage_exact() {
        // 결제 실패 1,500건(최신), 그보다 오래된 환불 실패 10건
        LocalDateTime base = LocalDateTime.of(2026, 11, 9, 12, 0);
        List<Payment> payments = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            payments.add(payment(10_000L - i, 3L, 500L + i, 1_000L, PaymentStatus.FAILED_SYSTEM_ERROR,
                    base.minusMinutes(i)));
        }
        List<PaymentRefund> refunds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            refunds.add(refund(900L - i, 300L + i, 13L, 1_000L, PaymentRefundStatus.FAILED_PLATFORM_BALANCE, 0,
                    base.minusDays(10).minusMinutes(i)));
        }
        stubFailures(payments, refunds);

        PageResponse<AdminFailureItem> page50 = useCase.getFailures(null, null, null, PageRequest.of(50, 20));

        // offset 1000: 결제 1001~1020번째(이전에는 상한 1,000 때문에 환불 10건이 나왔다)
        assertThat(page50.items()).extracting(AdminFailureItem::refId)
                .containsExactlyElementsOf(payments.subList(1_000, 1_020).stream().map(Payment::getId).toList());
        assertThat(page50.totalElements()).isEqualTo(1_510L);
        verify(paymentRepository).findFailures(anyCollection(), any(), any(), eq(PageRequest.of(0, 1_020,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))));
        verify(paymentRefundRepository).findFailures(anyCollection(), any(), any(), eq(PageRequest.of(0, 10,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))));   // 환불은 있는 만큼만

        PageResponse<AdminFailureItem> lastPage = useCase.getFailures(null, null, null, PageRequest.of(75, 20));
        assertThat(lastPage.items()).extracting(AdminFailureItem::type).containsOnly(AdminFailureType.REFUND_FAILED)
                .hasSize(10);
    }

    @Test
    @DisplayName("합쳐 볼 때 요청 페이지가 전체 건수를 넘으면 목록을 읽지 않고 빈 페이지와 전체 건수를 준다")
    void mergedBeyondTotal_emptyWithoutReading() {
        given(paymentRepository.countFailures(anyCollection(), any(), any())).willReturn(1_500L);
        given(paymentRefundRepository.countFailures(anyCollection(), any(), any())).willReturn(10L);

        PageResponse<AdminFailureItem> page = useCase.getFailures(null, null, null, PageRequest.of(76, 20));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1_510L);
        verify(paymentRepository, never()).findFailures(anyCollection(), any(), any(), any());
        verify(paymentRefundRepository, never()).findFailures(anyCollection(), any(), any(), any());
    }

    @Test
    @DisplayName("합쳐 볼 때 건수를 센 뒤 실패가 아니게 된 건이 있어 목록이 요청 위치보다 짧으면 오류 없이 빈 페이지를 준다")
    void mergedRowsShrunkAfterCount_emptyPage() {
        // 건수를 셀 때는 결제 20 + 환불 2 = 22건, 목록을 읽을 때는 환불 2건이 재시도로 성공해 결제 19건만 남음
        given(paymentRepository.countFailures(anyCollection(), any(), any())).willReturn(20L);
        given(paymentRefundRepository.countFailures(anyCollection(), any(), any())).willReturn(2L);
        List<Payment> remaining = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            remaining.add(payment(100L - i, 3L, 500L + i, 1_000L, PaymentStatus.FAILED_SYSTEM_ERROR,
                    LocalDateTime.of(2026, 11, 9, 12, 0).minusMinutes(i)));
        }
        given(paymentRepository.findFailures(anyCollection(), any(), any(), any()))
                .willReturn(new PageImpl<>(remaining, PageRequest.of(0, 20), 19));
        given(paymentRefundRepository.findFailures(anyCollection(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 2), 0));

        PageResponse<AdminFailureItem> page = useCase.getFailures(null, null, null, PageRequest.of(1, 20));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(22L);
    }

    /** 리포지토리 대역: 건수와, 최신순 목록의 앞에서 요청 크기만큼 */
    private void stubFailures(List<Payment> payments, List<PaymentRefund> refunds) {
        given(paymentRepository.countFailures(anyCollection(), any(), any())).willReturn((long) payments.size());
        given(paymentRefundRepository.countFailures(anyCollection(), any(), any())).willReturn((long) refunds.size());
        given(paymentRepository.findFailures(anyCollection(), any(), any(), any())).willAnswer(inv -> {
            Pageable p = inv.getArgument(3);
            return new PageImpl<>(payments.subList(0, Math.min(p.getPageSize(), payments.size())), p, payments.size());
        });
        given(paymentRefundRepository.findFailures(anyCollection(), any(), any(), any())).willAnswer(inv -> {
            Pageable p = inv.getArgument(3);
            return new PageImpl<>(refunds.subList(0, Math.min(p.getPageSize(), refunds.size())), p, refunds.size());
        });
    }

    @Test
    @DisplayName("이 API가 다루지 않는 type(충전, 정산, 배송)이면 빈 목록이다")
    void unsupportedType() {
        PageResponse<AdminFailureItem> page = useCase.getFailures(AdminFailureType.TOPUP_DELAYED, null, null,
                PageRequest.of(0, 20));

        assertThat(page.items()).isEmpty();
    }

    private static Payment payment(Long id, Long memberId, Long orderId, long amount, PaymentStatus status,
                                   LocalDateTime updatedAt) {
        Payment payment = BeanUtils.instantiateClass(Payment.class);
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "memberId", memberId);
        ReflectionTestUtils.setField(payment, "orderId", orderId);
        ReflectionTestUtils.setField(payment, "amount", amount);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "updatedAt", updatedAt);
        return payment;
    }

    private static PaymentRefund refund(Long id, Long orderId, Long paymentId, long requestedAmount,
                                        PaymentRefundStatus status, int retryCount, LocalDateTime updatedAt) {
        PaymentRefund refund = BeanUtils.instantiateClass(PaymentRefund.class);
        ReflectionTestUtils.setField(refund, "id", id);
        ReflectionTestUtils.setField(refund, "orderId", orderId);
        ReflectionTestUtils.setField(refund, "paymentId", paymentId);
        ReflectionTestUtils.setField(refund, "requestedAmount", requestedAmount);
        ReflectionTestUtils.setField(refund, "status", status);
        ReflectionTestUtils.setField(refund, "retryCount", retryCount);
        ReflectionTestUtils.setField(refund, "updatedAt", updatedAt);
        return refund;
    }
}
