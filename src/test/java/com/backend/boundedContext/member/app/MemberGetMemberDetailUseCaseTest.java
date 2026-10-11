package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

@ExtendWith(MockitoExtension.class)
class MemberGetMemberDetailUseCaseTest {

    @Mock
    private MemberSupport memberSupport;

    @Mock
    private MemberGetSellerUseCase memberGetSellerUseCase;

    @Mock
    private PaymentApi paymentApi;

    @InjectMocks
    private MemberGetMemberDetailUseCase memberGetMemberDetailUseCase;

    private final Member member = Member.signUp("user@palette.com", "encoded", "홍길동");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(member, "id", 3L);
    }

    @Test
    @DisplayName("회원 기본 정보, 판매자 상태, 지갑 잔액을 함께 돌려준다")
    void getMemberDetail_withSeller() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L))
                .willReturn(Optional.of(new SellerDto(10L, 3L, "팔레트상점", SellerStatus.PENDING)));
        given(paymentApi.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 50_000L, 0L));

        AdminMemberDetailDto detail = memberGetMemberDetailUseCase.getMemberDetail(3L);

        assertThat(detail.memberId()).isEqualTo(3L);
        assertThat(detail.role()).isEqualTo(MemberRole.USER);
        assertThat(detail.sellerStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(detail.wallet().balance()).isEqualTo(50_000L);
        assertThat(detail.wallet().heldAmount()).isZero();
    }

    @Test
    @DisplayName("판매자 신청 이력이 없으면 판매자 상태는 null")
    void getMemberDetail_noSeller() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L)).willReturn(Optional.empty());
        given(paymentApi.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 0L, 0L));

        assertThat(memberGetMemberDetailUseCase.getMemberDetail(3L).sellerStatus()).isNull();
    }

    @Test
    @DisplayName("회원이 없으면 404 MEMBER_NOT_FOUND이고 다른 컨텍스트는 호출하지 않는다")
    void getMemberDetail_notFound() {
        given(memberSupport.getMember(99L)).willThrow(new DomainException(MemberErrorCode.MEMBER_NOT_FOUND));

        assertThatThrownBy(() -> memberGetMemberDetailUseCase.getMemberDetail(99L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND.resultCode());

        verify(paymentApi, never()).getWallet(anyLong());
    }
}
