package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.shared.payment.out.PaymentApi;

/**
 * 회원 가입 → 지갑 생성이 한 트랜잭션으로 묶이는지 확인한다. 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class MemberSignUpIntegrationTest {

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private EmailVerificationStore emailVerificationStore;

    @MockitoSpyBean
    private PaymentApi paymentApi;

    private final String email = "signup-it-" + UUID.randomUUID() + "@palette.com";

    @AfterEach
    void cleanUp() {
        memberRepository.findByEmail(email).ifPresent(member -> {
            walletRepository.findByMemberId(member.getId()).ifPresent(walletRepository::delete);
            memberRepository.delete(member);
        });
    }

    private String issueVerifiedToken() {
        String token = UUID.randomUUID().toString();
        emailVerificationStore.saveVerifiedToken(token, email, Duration.ofMinutes(1));
        return token;
    }

    @Test
    @DisplayName("가입하면 회원과 잔액 0인 지갑이 함께 생성된다")
    void signUp_createsWallet() {
        Long memberId = memberFacade.signUp(email, "palette123!", "홍길동", issueVerifiedToken());

        assertThat(memberRepository.findById(memberId)).isPresent();
        assertThat(walletRepository.findByMemberId(memberId))
                .hasValueSatisfying(wallet -> assertThat(wallet.getBalance()).isZero());
    }

    @Test
    @DisplayName("지갑 생성이 실패하면 회원 저장도 함께 롤백된다")
    void signUp_rollsBackWhenWalletFails() {
        willThrow(new IllegalStateException("지갑 생성 실패")).given(paymentApi).createWallet(anyLong());

        assertThatThrownBy(() -> memberFacade.signUp(email, "palette123!", "홍길동", issueVerifiedToken()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(memberRepository.findByEmail(email)).isEmpty();
    }
}
