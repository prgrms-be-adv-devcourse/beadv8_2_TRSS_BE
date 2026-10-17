package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.page.PageResponse;

/**
 * 관리자 결제 조회 충전 탭을 실제 DB로 확인한다 (이메일·충전번호·기간 검색, 최신순, 이메일 채우기).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class AdminTopupQueryIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String email = "admin-topup-" + UUID.randomUUID().toString().substring(0, 8) + "@palette.test";
    private final List<Long> topupIds = new ArrayList<>();

    private Member member;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.signUp(email, "encoded", "충전조회"));
        wallet = walletRepository.save(Wallet.create(member.getId()));
    }

    @AfterEach
    void cleanUp() {
        topupRepository.deleteAllById(topupIds);
        walletRepository.delete(wallet);
        memberRepository.delete(member);
    }

    @Test
    @DisplayName("이메일로 검색하면 그 회원의 충전 건이 최신순으로 나오고 이메일이 채워진다")
    void searchByEmail() {
        TopupPrepareDto older = prepare(10_000);
        TopupPrepareDto newer = prepare(20_000);

        PageResponse<AdminTopupDto> result = paymentFacade.getAdminTopups(email, null, null, PageRequest.of(0, 20));

        assertThat(result.items()).extracting(AdminTopupDto::topupId).containsExactly(newer.topupId(), older.topupId());
        assertThat(result.items()).extracting(AdminTopupDto::memberEmail).containsOnly(email);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("충전번호로 검색하면 그 충전 건 하나만 나온다")
    void searchByOrderId() {
        prepare(10_000);
        TopupPrepareDto target = prepare(20_000);

        PageResponse<AdminTopupDto> result = paymentFacade.getAdminTopups(target.orderId(), null, null, PageRequest.of(0, 20));

        assertThat(result.items()).extracting(AdminTopupDto::topupId).containsExactly(target.topupId());
        assertThat(result.items().get(0).memberEmail()).isEqualTo(email);
    }

    @Test
    @DisplayName("기간을 지정하면 그 기간(양 끝 날짜 포함)에 준비된 충전 건만 나온다")
    void searchByPeriod() {
        TopupPrepareDto today = prepare(10_000);
        TopupPrepareDto threeDaysAgo = prepare(20_000);
        moveCreatedAt(threeDaysAgo.topupId(), LocalDateTime.now().minusDays(3));
        LocalDate todayDate = LocalDate.now();

        PageResponse<AdminTopupDto> recent = paymentFacade.getAdminTopups(email, todayDate, todayDate, PageRequest.of(0, 20));
        PageResponse<AdminTopupDto> all = paymentFacade.getAdminTopups(email, todayDate.minusDays(3), todayDate, PageRequest.of(0, 20));

        assertThat(recent.items()).extracting(AdminTopupDto::topupId).containsExactly(today.topupId());
        assertThat(all.items()).extracting(AdminTopupDto::topupId).containsExactlyInAnyOrder(today.topupId(), threeDaysAgo.topupId());
    }

    @Test
    @DisplayName("없는 이메일이나 지원하지 않는 검색어면 빈 페이지")
    void noResult() {
        prepare(10_000);

        assertThat(paymentFacade.getAdminTopups("nobody-" + UUID.randomUUID() + "@palette.test", null, null, PageRequest.of(0, 20)).items()).isEmpty();
        assertThat(paymentFacade.getAdminTopups("ORD-20261009-0142", null, null, PageRequest.of(0, 20)).items()).isEmpty();
    }

    private TopupPrepareDto prepare(long amount) {
        TopupPrepareDto prepared = paymentFacade.prepareTopup(member.getId(), amount);
        topupIds.add(prepared.topupId());
        return prepared;
    }

    // created_at은 앱(KST) 기준 LocalDateTime으로 저장되므로 앱 시각으로 넣음
    private void moveCreatedAt(Long topupId, LocalDateTime createdAt) {
        jdbcTemplate.update("update topup set created_at = ? where id = ?", createdAt, topupId);
    }
}
