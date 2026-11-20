package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 관리자 실패 거래(A7)의 정산 행: 수동 처리 필요 정산서를 공통 형식으로 바꾸는지, 기간과 정렬을 맞게 넘기는지
 */
@ExtendWith(MockitoExtension.class)
class SettlementGetFailuresUseCaseTest {

    @Mock SettlementRepository settlementRepository;
    @Mock SettlementSupport settlementSupport;
    @InjectMocks SettlementGetFailuresUseCase useCase;

    @Test
    @DisplayName("수동 처리 필요 정산서를 판매자 대상, 지급액, 실패 사유와 시도 횟수 원인, 월 정산 재처리로 돌려준다")
    void toAdminFailureItem() {
        Settlement settlement = manualRequired(31L, 7L, 415_000L, 3, LocalDateTime.of(2026, 11, 8, 9, 0));
        ReflectionTestUtils.setField(settlement, "lastFailReason", "홀딩의 남은 금액이 지급액보다 적습니다.");
        given(settlementRepository.findByStatusUpdatedBetween(eq(SettlementStatus.MANUAL_REQUIRED), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(settlement), PageRequest.of(0, 20), 1));
        given(settlementSupport.findBusinessNames(anyCollection())).willReturn(Map.of(7L, "팔레트상점"));

        PageResponse<AdminFailureItem> page = useCase.getFailures(null, null, PageRequest.of(0, 20));

        AdminFailureItem item = page.items().getFirst();
        assertThat(item.type()).isEqualTo(AdminFailureType.SETTLEMENT_MANUAL_REQUIRED);
        assertThat(item.occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 8, 9, 0));
        assertThat(item.targetType()).isEqualTo(AdminFailureTargetType.SELLER);
        assertThat(item.targetId()).isEqualTo(7L);
        assertThat(item.targetLabel()).isEqualTo("팔레트상점");
        assertThat(item.amount()).isEqualTo(415_000L);
        assertThat(item.reasonCode()).isEqualTo("MANUAL_REQUIRED");
        assertThat(item.reasonLabel()).isEqualTo("홀딩의 남은 금액이 지급액보다 적습니다. (시도 3번)");
        assertThat(item.handlingCode()).isEqualTo("MONTHLY_SETTLEMENT");
        assertThat(item.refId()).isEqualTo(31L);
        assertThat(item.orderId()).isNull();
    }

    @Test
    @DisplayName("기간은 from 00:00부터 to 다음 날 00:00 전까지, 최신순(updatedAt, id 내림차순)으로 조회한다")
    void rangeAndSort() {
        given(settlementRepository.findByStatusUpdatedBetween(any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        given(settlementSupport.findBusinessNames(anyCollection())).willReturn(Map.of());

        useCase.getFailures(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9),
                PageRequest.of(1, 10, Sort.by("id")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(settlementRepository).findByStatusUpdatedBetween(eq(SettlementStatus.MANUAL_REQUIRED),
                eq(LocalDateTime.of(2026, 11, 1, 0, 0)), eq(LocalDateTime.of(2026, 11, 10, 0, 0)), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort().getOrderFor("updatedAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("한 번에 수동 처리 필요가 된 실패(재시도해도 같은 실패)도 시도 횟수가 아니라 실패 사유가 먼저 보인다")
    void firstFailure_showsReasonNotRetryCount() {
        // markManualRequired도 시도 횟수를 1 올리므로 첫 실패여도 retryCount는 1이다
        Settlement settlement = manualRequired(32L, 8L, 10_000L, 1, LocalDateTime.of(2026, 11, 8, 9, 0));
        ReflectionTestUtils.setField(settlement, "lastFailReason", "판매자 지갑 없음");

        AdminFailureItem item = SettlementGetFailuresUseCase.toItem(settlement, null);

        assertThat(item.reasonLabel()).isEqualTo("판매자 지갑 없음 (시도 1번)");
        assertThat(item.targetLabel()).isNull();
    }

    @Test
    @DisplayName("실패 사유가 비어 있으면 기본 문구에 시도 횟수를 붙이고, 시도 횟수도 없으면 기본 문구만 쓴다")
    void blankReason_defaultLabel() {
        Settlement withAttempts = manualRequired(33L, 8L, 10_000L, 3, LocalDateTime.of(2026, 11, 8, 9, 0));
        ReflectionTestUtils.setField(withAttempts, "lastFailReason", " ");
        Settlement noAttempts = manualRequired(34L, 8L, 10_000L, 0, LocalDateTime.of(2026, 11, 8, 9, 0));

        assertThat(SettlementGetFailuresUseCase.toItem(withAttempts, null).reasonLabel()).isEqualTo("수동 처리 필요 (시도 3번)");
        assertThat(SettlementGetFailuresUseCase.toItem(noAttempts, null).reasonLabel()).isEqualTo("수동 처리 필요");
    }

    private static Settlement manualRequired(Long id, Long sellerId, long payout, int retryCount, LocalDateTime updatedAt) {
        Settlement settlement = Settlement.create(sellerId, "2026-10", null, null);
        ReflectionTestUtils.setField(settlement, "id", id);
        ReflectionTestUtils.setField(settlement, "status", SettlementStatus.MANUAL_REQUIRED);
        ReflectionTestUtils.setField(settlement, "payoutAmount", payout);
        ReflectionTestUtils.setField(settlement, "retryCount", retryCount);
        ReflectionTestUtils.setField(settlement, "updatedAt", updatedAt);
        return settlement;
    }
}
