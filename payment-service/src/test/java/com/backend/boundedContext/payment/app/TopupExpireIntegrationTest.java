package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;

/**
 * READY 만료 조건부 UPDATE를 실제 DB로 확인한다.
 * 로컬 DB에 남아 있는 다른 오래된 READY 건도 함께 만료되지만, 검증은 이 테스트가 만든 건만 한다.
 * 로컬 Docker PostgreSQL이 필요하다.
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

    @MockitoBean   // 회원 서비스 대신 쓰는 목. checkActiveMember는 아무것도 하지 않으므로 활성 회원으로 통과한다
    private PaymentMemberPort memberPort;

    // 회원은 회원 서비스에 있어 ID만 정하고, 활성 회원 확인은 위 목(memberPort)이 통과시킨다
    private Long memberId;
    private final List<Long> topupIds = new ArrayList<>();
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        memberId = randomMemberId();
        wallet = walletRepository.save(Wallet.create(memberId));
    }

    @AfterEach
    void cleanUp() {
        topupRepository.deleteAllById(topupIds);
        walletRepository.delete(wallet);
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

    // 다른 테스트·시드와 겹치지 않는 회원 ID (지갑의 member_id 유니크). 회원은 회원 서비스에 있어 ID만 정한다
    private static Long randomMemberId() {
        return ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);
    }
}
