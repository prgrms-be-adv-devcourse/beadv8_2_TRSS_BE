package com.backend.boundedContext.payment.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;

/**
 * 관리자 충전 확인 지연 조회(findDelayed): 결과 확인 대기 상태는 기준 시각 이전 것만, 실패는 지정한 상태만, 기간 안에서 최신순
 * 다른 테스트·시드 충전 건과 섞이지 않게 수정 시각을 2001-01-01로 옮기고 그 날짜만 조회한다
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupRepositoryFindDelayedTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2001, 1, 1, 0, 0);
    private static final List<TopupStatus> PENDING = List.of(TopupStatus.PROCESSING, TopupStatus.CANCELING, TopupStatus.METHOD_CANCELING);
    private static final List<TopupStatus> FAILED = List.of(TopupStatus.FAILED_SYSTEM_ERROR, TopupStatus.FAILED_AMOUNT_MISMATCH);
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> topupIds = new ArrayList<>();
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = walletRepository.save(Wallet.create(ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L)));
    }

    @AfterEach
    void cleanUp() {
        topupRepository.deleteAllById(topupIds);
        walletRepository.delete(wallet);
    }

    @Test
    @DisplayName("결과 확인 대기는 기준 시각 이전 것만, 실패는 시스템 오류·금액 불일치만, 기간 안에서 최신순으로 나온다")
    void findDelayed() {
        Long processing = saved(t -> t.markProcessing(pk(), LocalDateTime.now()), DAY.plusHours(10));
        saved(t -> { t.markProcessing(pk(), LocalDateTime.now()); t.approve(DAY); t.startCancel(); }, DAY.plusHours(13));   // 기준 시각(12시) 이후 CANCELING
        Long systemError = saved(t -> { t.markProcessing(pk(), LocalDateTime.now()); t.fail(TopupStatus.FAILED_SYSTEM_ERROR, "PG 인증에 실패했습니다."); }, DAY.plusHours(14));
        saved(t -> { t.markProcessing(pk(), LocalDateTime.now()); t.fail(TopupStatus.FAILED_PG_DECLINED, "카드 거절"); }, DAY.plusHours(15));
        saved(t -> t.fail(TopupStatus.FAILED_USER_CANCEL, "사용자 취소"), DAY.plusHours(16));
        saved(t -> { t.markProcessing(pk(), LocalDateTime.now()); t.approve(DAY); }, DAY.plusHours(9));   // DONE
        saved(t -> t.fail(TopupStatus.FAILED_AMOUNT_MISMATCH, "금액 다름"), DAY.plusDays(1).plusHours(1));   // 기간 밖

        Page<Topup> page = topupRepository.findDelayed(PENDING, DAY.plusHours(12), FAILED,
                DAY, DAY.plusDays(1), PageRequest.of(0, 20, LATEST_FIRST));

        assertThat(page.getContent()).extracting(Topup::getId).containsExactly(systemError, processing);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("수동 확인으로 표시된 결과 확인 대기 건은 기준 시각 이후여도 나온다")
    void findDelayed_manualCheck() {
        Long marked = saved(t -> { t.markProcessing(pk(), LocalDateTime.now()); t.approve(DAY); t.startCancel();
            t.markManualCheck(TopupManualCheckReason.CANCEL_AMOUNT_MISMATCH, DAY); }, DAY.plusHours(13));
        saved(t -> { t.markProcessing(pk(), LocalDateTime.now()); t.approve(DAY); t.startCancel(); }, DAY.plusHours(13));

        Page<Topup> page = topupRepository.findDelayed(PENDING, DAY.plusHours(12), FAILED,
                DAY, DAY.plusDays(1), PageRequest.of(0, 20, LATEST_FIRST));

        assertThat(page.getContent()).extracting(Topup::getId).containsExactly(marked);
    }

    // 상태를 바꾼 충전 건을 저장하고 수정 시각을 옮김
    private Long saved(Consumer<Topup> toStatus, LocalDateTime updatedAt) {
        Topup topup = Topup.create(wallet.getMemberId(), wallet, 10_000, PgProvider.TOSS, LocalDateTime.now());
        toStatus.accept(topup);
        Long id = topupRepository.save(topup).getId();
        topupIds.add(id);
        jdbcTemplate.update("update topup set updated_at = ? where id = ?", updatedAt, id);
        return id;
    }

    private static String pk() {
        return "TEST-PK-" + UUID.randomUUID();
    }
}
