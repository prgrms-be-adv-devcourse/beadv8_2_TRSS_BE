package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;

/**
 * READY 만료 조건부 UPDATE를 실제 DB로 확인한다.
 * 로컬 DB에 남아 있는 다른 오래된 READY 건도 함께 만료되지만, 검증은 이 테스트가 만든 건만 한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupExpireIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberRepository memberRepository;

    // 충전은 로그인 상태인 회원만 할 수 있어 실제 회원을 만든다
    private Long memberId;
    private final List<Long> topupIds = new ArrayList<>();
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.save(Member.signUp("topup-it-" + UUID.randomUUID() + "@palette.com", "encoded", "회원")).getId();
        wallet = walletRepository.save(Wallet.create(memberId));
    }

    @AfterEach
    void cleanUp() {
        topupRepository.deleteAllById(topupIds);
        walletRepository.delete(wallet);
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("만료 시간이 지난 READY만 FAILED_EXPIRED가 되고, 최근 READY와 오래된 PROCESSING은 그대로다")
    void expireReadyTopups() {
        Long oldReady = prepare();
        Long recentReady = prepare();
        Long oldProcessing = prepare();
        moveCreatedAtMinutesAgo(oldReady, 16);
        moveCreatedAtMinutesAgo(oldProcessing, 16);
        jdbcTemplate.update("update topup set status = 'PROCESSING', payment_key = ? where id = ?",
                "pk_expire_test_" + oldProcessing, oldProcessing);

        int expired = paymentFacade.expireReadyTopups();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        Topup expiredTopup = topupRepository.findById(oldReady).orElseThrow();
        assertThat(expiredTopup.getStatus()).isEqualTo(TopupStatus.FAILED_EXPIRED);
        assertThat(expiredTopup.getFailReason()).isNotBlank();
        assertThat(expiredTopup.getUpdatedAt()).isAfter(expiredTopup.getCreatedAt());
        assertThat(topupRepository.findById(recentReady).orElseThrow().getStatus()).isEqualTo(TopupStatus.READY);
        assertThat(topupRepository.findById(oldProcessing).orElseThrow().getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    private Long prepare() {
        Long topupId = paymentFacade.prepareTopup(memberId, 10_000).topupId();
        topupIds.add(topupId);
        return topupId;
    }

    // created_at은 앱(KST) 기준 LocalDateTime으로 저장되므로, DB now()가 아니라 앱 시각으로 과거 시각을 넣음
    private void moveCreatedAtMinutesAgo(Long topupId, int minutes) {
        jdbcTemplate.update("update topup set created_at = ? where id = ?", LocalDateTime.now().minusMinutes(minutes), topupId);
    }
}
