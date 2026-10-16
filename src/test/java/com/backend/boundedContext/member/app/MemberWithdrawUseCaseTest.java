package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.app.dto.WithdrawalNotAllowedResponse;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.domain.WithdrawalBlockReason;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final String EMAIL = "buyer@palette.test";

    @Mock
    private MemberSupport memberSupport;

    @Mock
    private MemberWithdrawalSupport memberWithdrawalSupport;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginAttemptStore loginAttemptStore;

    // 로그인 최대 실패 5회, 잠금 15분
    @Spy
    private MemberPolicy memberPolicy = new MemberPolicy(5, 60, 30, 5, 5, 15, 14, false, "Lax");

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    @InjectMocks
    private MemberWithdrawUseCase memberWithdrawUseCase;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.signUp(EMAIL, "encoded", "구매자");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        given(memberSupport.getLoginMember(MEMBER_ID)).willReturn(member);
    }

    @Test
    @DisplayName("비밀번호가 맞고 조건을 충족하면 회원을 비식별화하고 실패 횟수를 지운다")
    void withdraw_success() {
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberWithdrawalSupport.check(MEMBER_ID)).willReturn(WithdrawalCheckDto.of(false, 0, 0, false));
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        memberWithdrawUseCase.withdraw(MEMBER_ID, "palette123!");

        assertThat(member.getStatus()).isEqualTo(MemberStatus.DELETED);
        assertThat(member.getEmail()).isEqualTo("deleted-3@deleted.palette");
        verify(loginAttemptStore).deleteFailCount(EMAIL);
    }

    @Test
    @DisplayName("판매자 정보가 있으면 판매자와 상태 이력도 비식별화한다")
    void withdraw_sellerAnonymized() {
        Seller seller = Seller.apply(member, "팔레트 뷰티", "1234567890", "홍길동", "01012345678",
                "국민은행", "12345601123456", "홍길동");
        ReflectionTestUtils.setField(seller, "id", 10L);
        SellerStatusHistory rejected = seller.reject("사업자번호 불일치");
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberWithdrawalSupport.check(MEMBER_ID)).willReturn(WithdrawalCheckDto.of(false, 0, 0, false));
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(10L)).willReturn(List.of(rejected));

        memberWithdrawUseCase.withdraw(MEMBER_ID, "palette123!");

        assertThat(seller.getRepresentativeName()).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat(seller.getAccountNumber()).isEqualTo("0");
        assertThat(rejected.getRepresentativeName()).isNull();
        assertThat(rejected.getAccountLast4()).isNull();
    }

    @Test
    @DisplayName("조건이 남아 있으면 409 WITHDRAWAL_NOT_ALLOWED이고 data.reasons에 남은 조건을 담는다 (회원은 그대로)")
    void withdraw_notAllowed() {
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberWithdrawalSupport.check(MEMBER_ID)).willReturn(WithdrawalCheckDto.of(true, 0, 10_000, false));

        assertThatThrownBy(() -> memberWithdrawUseCase.withdraw(MEMBER_ID, "palette123!"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException de = (DomainException) e;
                    assertThat(de.getResultCode()).isEqualTo(MemberErrorCode.WITHDRAWAL_NOT_ALLOWED.resultCode());
                    assertThat(((WithdrawalNotAllowedResponse) de.getData()).reasons())
                            .containsExactly(WithdrawalBlockReason.ACTIVE_ORDERS, WithdrawalBlockReason.BALANCE_REMAINING);
                });
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 400 PASSWORD_MISMATCH이고 로그인과 같은 실패 횟수를 올린다 (조건은 확인하지 않음)")
    void withdraw_wrongPassword() {
        given(passwordEncoder.matches("wrong123!", "encoded")).willReturn(false);
        given(loginAttemptStore.increaseFailCount(EMAIL, memberPolicy.loginLockTtl())).willReturn(1L);

        assertError(MemberErrorCode.PASSWORD_MISMATCH);
        verify(memberWithdrawalSupport, never()).check(anyLong());
    }

    @Test
    @DisplayName("이번 실패로 5회가 되면 잠그고 429 LOGIN_LOCKED")
    void withdraw_fifthFailure_locks() {
        given(passwordEncoder.matches("wrong123!", "encoded")).willReturn(false);
        given(loginAttemptStore.increaseFailCount(EMAIL, memberPolicy.loginLockTtl())).willReturn(5L);

        assertError(MemberErrorCode.LOGIN_LOCKED);
        verify(loginAttemptStore).lock(EMAIL, memberPolicy.loginLockTtl());
    }

    @Test
    @DisplayName("잠긴 이메일이면 비밀번호를 확인하지 않고 429 LOGIN_LOCKED")
    void withdraw_locked() {
        given(loginAttemptStore.getFailCount(EMAIL)).willReturn(5L);

        assertError(MemberErrorCode.LOGIN_LOCKED);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    private void assertError(MemberErrorCode errorCode) {
        assertThatThrownBy(() -> memberWithdrawUseCase.withdraw(MEMBER_ID, "wrong123!"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
