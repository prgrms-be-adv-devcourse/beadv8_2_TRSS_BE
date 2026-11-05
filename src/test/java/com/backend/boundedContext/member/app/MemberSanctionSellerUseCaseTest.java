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
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.event.SellerSanctionedEvent;

@ExtendWith(MockitoExtension.class)
class MemberSanctionSellerUseCaseTest {

    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerSupport sellerSupport;

    @Mock
    private MemberSettlementPort memberSettlementPort;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private MemberSanctionSellerUseCase memberSanctionSellerUseCase;

    @Test
    @DisplayName("판매 중이면 SANCTIONED가 되고, 정산 보류를 사유와 함께 요청하고 제재 이벤트를 발행한 뒤 이력을 저장한다")
    void sanction_approved() {
        Seller seller = approved();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        memberSanctionSellerUseCase.sanction(SELLER_ID, " 위조 상품 판매 ");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.SANCTIONED);
        assertThat(seller.getBusinessNumber()).isEqualTo("1234567890");
        InOrder inOrder = inOrder(memberSettlementPort, eventPublisher);
        inOrder.verify(memberSettlementPort).holdSeller(SELLER_ID, "위조 상품 판매");
        inOrder.verify(eventPublisher).publish(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"));
        verify(sellerSupport).saveHistory(any(), any());
    }

    @Test
    @DisplayName("철회 심사 중이어도 바로 제재할 수 있다")
    void sanction_withdrawRequested() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        memberSanctionSellerUseCase.sanction(SELLER_ID, "위조 상품 판매");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.SANCTIONED);
        verify(memberSettlementPort).holdSeller(SELLER_ID, "위조 상품 판매");
        verify(eventPublisher).publish(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"));
    }

    @Test
    @DisplayName("판매 중·철회 심사 중이 아니면 409 SELLER_STATE_INVALID이고 정산 보류를 요청하지 않고 제재 이벤트도 발행하지 않는다")
    void sanction_stateInvalid() {
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(pending());

        assertThatThrownBy(() -> memberSanctionSellerUseCase.sanction(SELLER_ID, "위조 상품 판매"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_STATE_INVALID.resultCode()));

        verifyNoInteractions(memberSettlementPort, eventPublisher);
        verify(sellerSupport, never()).saveHistory(any(), any());
    }

    private static Seller approved() {
        Seller seller = pending();
        seller.approve();
        return seller;
    }

    private static Seller pending() {
        return Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
    }
}
