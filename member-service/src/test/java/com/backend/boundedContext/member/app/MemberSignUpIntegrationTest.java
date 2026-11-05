package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;

/**
 * 회원 가입 → 가입 완료 이벤트(outbox) 저장을 확인한다. 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 지갑은 가입 트랜잭션에서 만들지 않는다. 릴레이가 Kafka(member.events)로 보내면 결제 컨슈머가 만든다
 * (PaymentEventListenerKafkaIntegrationTest에서 확인).
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class MemberSignUpIntegrationTest {

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EmailVerificationStore emailVerificationStore;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private final String email = "signup-it-" + UUID.randomUUID() + "@palette.com";

    @AfterEach
    void cleanUp() {
        memberRepository.findByEmail(email).ifPresent(member -> {
            outboxEventRepository.deleteAll(signUpOutbox(member.getId()));
            memberRepository.delete(member);
        });
    }

    @Test
    @DisplayName("가입하면 회원이 저장되고 가입 이벤트가 outbox에 PENDING으로 남는다")
    void signUp_savesSignUpEvent() {
        Long memberId = memberFacade.signUp(email, "palette123!", "홍길동", issueVerifiedToken());

        assertThat(memberRepository.findById(memberId)).isPresent();
        assertThat(signUpOutbox(memberId)).singleElement()
                .satisfies(outbox -> {
                    assertThat(outbox.getEventType()).isEqualTo("MemberSignedUpEvent");
                    assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
                });
    }

    private String issueVerifiedToken() {
        String token = UUID.randomUUID().toString();
        emailVerificationStore.saveVerifiedToken(token, email, Duration.ofMinutes(1));
        return token;
    }

    private List<OutboxEvent> signUpOutbox(Long memberId) {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("MEMBER") && outbox.getAggregateId().equals(memberId))
                .toList();
    }
}
