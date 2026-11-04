package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.MemberProductPort;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.event.SellerWithdrawalRejectedEvent;

@ExtendWith(MockitoExtension.class)
class MemberRejectWithdrawalUseCaseTest {

    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerSupport sellerSupport;

    @Mock
    private MemberProductPort memberProductPort;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private MemberRejectWithdrawalUseCase memberRejectWithdrawalUseCase;

    @Test
    @DisplayName("철회 심사 중이면 APPROVED로 돌아가고, 철회로 중지된 상품의 판매 재개를 요청하고 철회 거절 이벤트를 발행한 뒤 이력을 저장한다")
    void rejectWithdrawal_success() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        memberRejectWithdrawalUseCase.rejectWithdrawal(SELLER_ID, " 진행 중인 반품 처리 후 다시 요청해 주세요 ");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        InOrder inOrder = inOrder(memberProductPort, eventPublisher);
        inOrder.verify(memberProductPort).resumeAllBySeller(SELLER_ID);
        inOrder.verify(eventPublisher).publish(new SellerWithdrawalRejectedEvent(SELLER_ID));
        verify(sellerSupport).saveHistory(any(), any());
    }

    @Test
    @DisplayName("철회 심사 중이 아니면 409 SELLER_STATE_INVALID이고 판매 재개 요청도 이벤트 발행도 하지 않는다")
    void rejectWithdrawal_notRequested() {
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(approved());

        assertThatThrownBy(() -> memberRejectWithdrawalUseCase.rejectWithdrawal(SELLER_ID, "거절"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_STATE_INVALID.resultCode()));

        verifyNoInteractions(memberProductPort, eventPublisher);
        verify(sellerSupport, never()).saveHistory(any(), any());
    }

    private static Seller approved() {
        Seller seller = Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        seller.approve();
        return seller;
    }
}
