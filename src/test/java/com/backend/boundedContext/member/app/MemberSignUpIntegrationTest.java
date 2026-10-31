package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.global.outbox.OutboxRelay;

/**
 * 회원 가입 → 가입 완료 이벤트(outbox) → 릴레이 → 지갑 생성을 확인한다.
 * 가입과 지갑 생성은 서로 다른 트랜잭션이라, 지갑 생성이 실패해도 가입은 성공한다.
 * 스케줄러는 끄고 릴레이를 직접 호출한다. 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class MemberSignUpIntegrationTest {

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private EmailVerificationStore emailVerificationStore;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @MockitoSpyBean
    private PaymentFacade paymentFacade;

    private final String email = "signup-it-" + UUID.randomUUID() + "@palette.com";

    @AfterEach
    void cleanUp() {
        memberRepository.findByEmail(email).ifPresent(member -> {
            outboxEventRepository.deleteAll(signUpOutbox(member.getId()));
            walletRepository.findByMemberId(member.getId()).ifPresent(walletRepository::delete);
            memberRepository.delete(member);
        });
    }

    @Test
    @DisplayName("가입하면 가입 이벤트만 PENDING으로 남고, 릴레이가 전달하면 잔액 0인 지갑이 생긴다")
    void signUp_createsWalletAfterRelay() {
        Long memberId = memberFacade.signUp(email, "palette123!", "홍길동", issueVerifiedToken());

        assertThat(memberRepository.findById(memberId)).isPresent();
        assertThat(walletRepository.findByMemberId(memberId)).isEmpty();

        OutboxEvent relayed = relayUntilDone(memberId);

        assertThat(relayed.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(walletRepository.findByMemberId(memberId))
                .hasValueSatisfying(wallet -> assertThat(wallet.getBalance()).isZero());
    }

    @Test
    @DisplayName("지갑 생성이 실패해도 가입은 성공하고, 가입 이벤트는 PENDING으로 남아 다음 릴레이에서 재시도한다")
    void signUp_succeedsWhenWalletFails() {
        willThrow(new IllegalStateException("지갑 생성 실패")).given(paymentFacade).createWallet(anyLong());

        Long memberId = memberFacade.signUp(email, "palette123!", "홍길동", issueVerifiedToken());
        OutboxEvent outbox = relayUntil(memberId, o -> o.getRetryCount() > 0);

        assertThat(memberRepository.findById(memberId)).isPresent();
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(walletRepository.findByMemberId(memberId)).isEmpty();
    }

    private String issueVerifiedToken() {
        String token = UUID.randomUUID().toString();
        emailVerificationStore.saveVerifiedToken(token, email, Duration.ofMinutes(1));
        return token;
    }

    private OutboxEvent relayUntilDone(Long memberId) {
        return relayUntil(memberId, o -> o.getStatus() != OutboxEventStatus.PENDING);
    }

    // 로컬 DB에 남은 다른 PENDING 이벤트가 많으면 한 번에 닿지 않을 수 있어, 조건을 만족할 때까지 릴레이를 다시 돌린다
    private OutboxEvent relayUntil(Long memberId, Predicate<OutboxEvent> condition) {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            OutboxEvent outbox = signUpOutbox(memberId).getFirst();
            if (condition.test(outbox)) return outbox;
        }
        throw new AssertionError("릴레이를 100번 돌려도 조건을 만족하지 않습니다: memberId=" + memberId);
    }

    private List<OutboxEvent> signUpOutbox(Long memberId) {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("MEMBER") && outbox.getAggregateId().equals(memberId))
                .toList();
    }
}
