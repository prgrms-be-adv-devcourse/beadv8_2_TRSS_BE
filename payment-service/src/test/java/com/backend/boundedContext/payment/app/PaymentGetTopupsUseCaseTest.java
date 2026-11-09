package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.page.PageResponse;

class PaymentGetTopupsUseCaseTest {

    private static final Long MEMBER_ID = 3L;

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentGetTopupsUseCase useCase = new PaymentGetTopupsUseCase(topupRepository);

    @Test
    @DisplayName("요청한 정렬은 무시하고 최신순(createdAt, id 역순)으로 내 충전 건을 조회해 목록 항목으로 바꾼다")
    void getTopups() {
        Topup topup = Topup.create(MEMBER_ID, Wallet.create(MEMBER_ID), 20_000);
        given(topupRepository.findByMemberId(eq(MEMBER_ID), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(topup), invocation.getArgument(1), 1));

        PageResponse<TopupDto> result = useCase.getTopups(MEMBER_ID, PageRequest.of(1, 10, Sort.by("amount")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(topupRepository).findByMemberId(eq(MEMBER_ID), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        assertThat(result.items()).hasSize(1);
        TopupDto item = result.items().get(0);
        assertThat(item.orderId()).isEqualTo(topup.getTossOrderId());
        assertThat(item.amount()).isEqualTo(20_000);
        assertThat(item.remainingAmount()).isZero();
        assertThat(item.status()).isEqualTo(TopupStatus.READY);
        assertThat(item.failReason()).isNull();
    }
}
