package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.member.dto.MemberDto;

/**
 * 관리자 실패 거래(A7)의 충전 확인 지연 행: 조회 조건, 공통 형식 변환, 처리 칸 기준(60분), 실패 사유 붙이기
 */
class PaymentGetTopupFailuresUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 12, 0);

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentMemberPort memberPort = mock(PaymentMemberPort.class);
    // 결과 확인 대기 5분, 오래 남은 건 경고 60분
    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 3, 60, 1L, 2_500);
    private final PaymentGetTopupFailuresUseCase useCase = new PaymentGetTopupFailuresUseCase(topupRepository, memberPort, paymentPolicy);

    @BeforeEach
    void setUp() {
        given(memberPort.getMembers(anyCollection())).willReturn(List.of(new MemberDto(MEMBER_ID, "buyer@palette.test", "구매자", null, null)));
    }

    @Test
    @DisplayName("결과 확인 대기는 5분 전 이전 것만, 실패는 시스템 오류·금액 불일치만 찾고, 기간은 from 00:00부터 to 다음 날 00:00 전까지 최신순이다")
    void query() {
        given(topupRepository.findDelayed(anyCollection(), any(), anyCollection(), any(), any(), any())).willReturn(page());

        useCase.getFailures(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18), PageRequest.of(1, 10, Sort.by("amount")), NOW);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(topupRepository).findDelayed(eq(PaymentGetTopupFailuresUseCase.PENDING_STATUSES), eq(NOW.minusMinutes(5)),
                eq(PaymentGetTopupFailuresUseCase.FAILED_STATUSES),
                eq(LocalDateTime.of(2026, 11, 1, 0, 0)), eq(LocalDateTime.of(2026, 11, 19, 0, 0)), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        assertThat(PaymentGetTopupFailuresUseCase.FAILED_STATUSES)
                .doesNotContain(TopupStatus.FAILED_USER_CANCEL, TopupStatus.FAILED_EXPIRED, TopupStatus.FAILED_PG_DECLINED, TopupStatus.FAILED_METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("결과 확인 대기는 회원 대상, 충전 금액, 상태 문구로 바꾸고, 60분 이하로 남았으면 자동 확인 대기다")
    void pending_autoRecheck() {
        Topup topup = topup(TopupStatus.PROCESSING, NOW.minusMinutes(60));
        given(topupRepository.findDelayed(anyCollection(), any(), anyCollection(), any(), any(), any())).willReturn(page(topup));

        AdminFailureItem item = first(useCase.getFailures(null, null, PageRequest.of(0, 20), NOW));

        assertThat(item.type()).isEqualTo(AdminFailureType.TOPUP_DELAYED);
        assertThat(item.occurredAt()).isEqualTo(NOW.minusMinutes(60));
        assertThat(item.targetType()).isEqualTo(AdminFailureTargetType.MEMBER);
        assertThat(item.targetId()).isEqualTo(MEMBER_ID);
        assertThat(item.targetLabel()).isEqualTo("bu***@palette.test");
        assertThat(item.amount()).isEqualTo(20_000L);
        assertThat(item.reasonCode()).isEqualTo("PROCESSING");
        assertThat(item.reasonLabel()).isEqualTo("PG 승인 결과 확인 중");
        assertThat(item.handlingCode()).isEqualTo("AUTO_RECHECK");
        assertThat(item.handlingLabel()).isEqualTo("자동 확인 대기");
        assertThat(item.refId()).isEqualTo(7L);
        assertThat(item.orderId()).isNull();
    }

    @Test
    @DisplayName("결과 확인 대기로 60분 넘게 남았으면 대사가 오래 못 정한 것이라 확인 필요다")
    void pending_stuck() {
        Topup topup = topup(TopupStatus.CANCELING, NOW.minusMinutes(61));
        given(topupRepository.findDelayed(anyCollection(), any(), anyCollection(), any(), any(), any())).willReturn(page(topup));

        AdminFailureItem item = first(useCase.getFailures(null, null, PageRequest.of(0, 20), NOW));

        assertThat(item.reasonLabel()).isEqualTo("PG 취소 결과 확인 중");
        assertThat(item.handlingCode()).isEqualTo("CHECK_REQUIRED");
        assertThat(item.handlingLabel()).isEqualTo("확인 필요");
    }

    @Test
    @DisplayName("실패는 시간과 관계없이 확인 필요이고, 원인 문구에 저장된 실패 사유를 붙인다")
    void failed() {
        Topup topup = topup(TopupStatus.FAILED_SYSTEM_ERROR, NOW.minusMinutes(1));
        given(topupRepository.findDelayed(anyCollection(), any(), anyCollection(), any(), any(), any())).willReturn(page(topup));

        AdminFailureItem item = first(useCase.getFailures(null, null, PageRequest.of(0, 20), NOW));

        assertThat(item.reasonCode()).isEqualTo("FAILED_SYSTEM_ERROR");
        assertThat(item.reasonLabel()).isEqualTo("시스템 오류 (사유)");
        assertThat(item.handlingCode()).isEqualTo("CHECK_REQUIRED");
    }

    @Test
    @DisplayName("수동 확인으로 표시된 건은 60분이 안 됐어도 확인 필요이고, 원인 문구에 수동 확인 사유를 붙인다")
    void manualCheck() {
        Topup topup = topup(TopupStatus.METHOD_CANCELING, NOW.minusMinutes(10));
        topup.markManualCheck(TopupManualCheckReason.METHOD_CANCEL_PARTIAL, NOW.minusMinutes(10));
        given(topupRepository.findDelayed(anyCollection(), any(), anyCollection(), any(), any(), any())).willReturn(page(topup));

        AdminFailureItem item = first(useCase.getFailures(null, null, PageRequest.of(0, 20), NOW));

        assertThat(item.reasonCode()).isEqualTo("METHOD_CANCELING");
        assertThat(item.reasonLabel()).isEqualTo("허용하지 않은 결제 수단 취소 결과 확인 중 (수동 확인: 일부만 취소됨)");
        assertThat(item.handlingCode()).isEqualTo("CHECK_REQUIRED");
        assertThat(item.handlingLabel()).isEqualTo("확인 필요");
    }

    @Test
    @DisplayName("회원 이메일을 찾지 못하면 대상 이름은 null이다")
    void memberNotFound() {
        given(memberPort.getMembers(anyCollection())).willReturn(List.of());
        given(topupRepository.findDelayed(anyCollection(), any(), anyCollection(), any(), any(), any()))
                .willReturn(page(topup(TopupStatus.PROCESSING, NOW.minusMinutes(10))));

        assertThat(first(useCase.getFailures(null, null, PageRequest.of(0, 20), NOW)).targetLabel()).isNull();
    }

    @Test
    @DisplayName("상태별 원인 문구: 금액 불일치는 사유를 붙이고, 사유가 비면 상태 문구만 쓴다")
    void reasonLabel() {
        assertThat(PaymentGetTopupFailuresUseCase.reasonLabel(topup(TopupStatus.METHOD_CANCELING, NOW)))
                .isEqualTo("허용하지 않은 결제 수단 취소 결과 확인 중");
        assertThat(PaymentGetTopupFailuresUseCase.reasonLabel(topup(TopupStatus.FAILED_AMOUNT_MISMATCH, NOW)))
                .isEqualTo("금액 불일치 (사유)");

        Topup noReason = topup(TopupStatus.FAILED_SYSTEM_ERROR, NOW);
        ReflectionTestUtils.setField(noReason, "failReason", null);
        assertThat(PaymentGetTopupFailuresUseCase.reasonLabel(noReason)).isEqualTo("시스템 오류");
    }

    // 상태·수정 시각을 직접 넣은 충전 건 (실패 상태면 실패 사유 "사유")
    private static Topup topup(TopupStatus status, LocalDateTime updatedAt) {
        Topup topup = Topup.create(MEMBER_ID, Wallet.create(MEMBER_ID), 20_000, PgProvider.TOSS, NOW);
        ReflectionTestUtils.setField(topup, "id", 7L);
        ReflectionTestUtils.setField(topup, "status", status);
        ReflectionTestUtils.setField(topup, "updatedAt", updatedAt);
        if (status.isFailure()) ReflectionTestUtils.setField(topup, "failReason", "사유");
        return topup;
    }

    private static PageImpl<Topup> page(Topup... topups) {
        return new PageImpl<>(List.of(topups), PageRequest.of(0, 20), topups.length);
    }

    private static AdminFailureItem first(PageResponse<AdminFailureItem> page) {
        return page.items().getFirst();
    }
}
