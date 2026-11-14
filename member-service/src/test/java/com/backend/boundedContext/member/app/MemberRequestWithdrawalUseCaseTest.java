package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.MemberPortUnavailableException;
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;

@ExtendWith(MockitoExtension.class)
class MemberRequestWithdrawalUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    @Mock
    private MemberSettlementPort memberSettlementPort;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private MemberRequestWithdrawalUseCase memberRequestWithdrawalUseCase;

    @Test
    @DisplayName("판매 중이고 미정산 금액이 없으면 WITHDRAW_REQUESTED가 되고, 이력을 저장한 뒤 사유가 담긴 철회 요청 이벤트를 발행한다")
    void requestWithdrawal_success() {
        Seller seller = seller();
        seller.approve();
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willReturn(0L);

        memberRequestWithdrawalUseCase.requestWithdrawal(MEMBER_ID, " 폐업 예정 ");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        verify(sellerStatusHistoryRepository).save(any());
        InOrder inOrder = inOrder(eventPublisher);
        inOrder.verify(eventPublisher).publish(new SellerWithdrawalRequestedEvent(SELLER_ID, "폐업 예정"));
    }

    @Test
    @DisplayName("미정산 금액이 있으면 409 UNSETTLED_AMOUNT_EXISTS이고 철회 요청 이벤트를 발행하지 않는다")
    void requestWithdrawal_unsettledAmount() {
        Seller seller = seller();
        seller.approve();
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willReturn(15_000L);

        assertError(MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("판매 중이 아니면 409 SELLER_STATE_INVALID이고 철회 요청 이벤트를 발행하지 않는다")
    void requestWithdrawal_notApproved() {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller()));   // PENDING
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willReturn(0L);

        assertError(MemberErrorCode.SELLER_STATE_INVALID);

        verify(sellerStatusHistoryRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("정산을 조회하지 못하면 503 WITHDRAWAL_CHECK_UNAVAILABLE이고 철회 요청 이벤트를 발행하지 않는다")
    void requestWithdrawal_checkUnavailable() {
        Seller seller = seller();
        seller.approve();
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID))
                .willThrow(new MemberPortUnavailableException("시간 초과", null));

        assertError(MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        verifyNoInteractions(eventPublisher);
    }

    private void assertError(MemberErrorCode errorCode) {
        assertThatThrownBy(() -> memberRequestWithdrawalUseCase.requestWithdrawal(MEMBER_ID, "폐업 예정"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }

    private static Seller seller() {
        Seller seller = Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        ReflectionTestUtils.setField(seller, "id", SELLER_ID);
        return seller;
    }
}
