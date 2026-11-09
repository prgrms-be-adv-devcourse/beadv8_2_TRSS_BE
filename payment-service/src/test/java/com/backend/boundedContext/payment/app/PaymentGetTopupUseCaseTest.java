package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.exception.DomainException;

class PaymentGetTopupUseCaseTest {

    private static final Long MEMBER_ID = 3L;

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentGetTopupUseCase useCase = new PaymentGetTopupUseCase(topupRepository);

    @Test
    @DisplayName("내 충전 건이면 목록 항목과 같은 형식으로 돌려준다")
    void getTopup() {
        Topup topup = Topup.create(MEMBER_ID, Wallet.create(MEMBER_ID), 20_000);
        topup.fail(TopupStatus.FAILED_USER_CANCEL, "사용자에 의해 결제가 취소되었습니다.");
        given(topupRepository.findByIdAndMemberId(7L, MEMBER_ID)).willReturn(Optional.of(topup));

        TopupDto result = useCase.getTopup(MEMBER_ID, 7L);

        assertThat(result.orderId()).isEqualTo(topup.getTossOrderId());
        assertThat(result.status()).isEqualTo(TopupStatus.FAILED_USER_CANCEL);
        assertThat(result.failReason()).isEqualTo("사용자에 의해 결제가 취소되었습니다.");
    }

    @Test
    @DisplayName("없거나 남의 충전 건이면 404 TOPUP_NOT_FOUND")
    void getTopup_notFound() {
        given(topupRepository.findByIdAndMemberId(7L, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getTopup(MEMBER_ID, 7L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }
}
