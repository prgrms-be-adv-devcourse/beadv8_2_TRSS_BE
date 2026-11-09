package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;

/**
 * 충전 내역 조회·실패 기록을 실제 DB로 확인한다 (회원별 조회, 최신순, 페이징, 남의 충전 건 404).
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupHistoryIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @MockitoBean   // 회원 서비스 대신 쓰는 목. checkActiveMember는 아무것도 하지 않으므로 활성 회원으로 통과한다
    private PaymentMemberPort memberPort;

    // 회원은 회원 서비스에 있어 ID만 정하고, 활성 회원 확인은 위 목(memberPort)이 통과시킨다
    private Long memberId;
    private Long otherMemberId;

    private final List<Wallet> wallets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memberId = randomMemberId();
        otherMemberId = randomMemberId();
    }

    @AfterEach
    void cleanUp() {
        topupRepository.deleteAll(topupRepository.findAll().stream()
                .filter(topup -> topup.getMemberId().equals(memberId) || topup.getMemberId().equals(otherMemberId))
                .toList());
        walletRepository.deleteAll(wallets);
    }

    @Test
    @DisplayName("내 충전 건만 최신순으로 페이지 단위로 조회된다")
    void getTopups() {
        wallets.add(walletRepository.save(Wallet.create(memberId)));
        wallets.add(walletRepository.save(Wallet.create(otherMemberId)));
        Long first = paymentFacade.prepareTopup(memberId, 10_000).topupId();
        Long second = paymentFacade.prepareTopup(memberId, 20_000).topupId();
        Long third = paymentFacade.prepareTopup(memberId, 30_000).topupId();
        paymentFacade.prepareTopup(otherMemberId, 40_000);

        PageResponse<TopupDto> page0 = paymentFacade.getTopups(memberId, PageRequest.of(0, 2));
        PageResponse<TopupDto> page1 = paymentFacade.getTopups(memberId, PageRequest.of(1, 2));

        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.items()).extracting(TopupDto::topupId).containsExactly(third, second);
        assertThat(page1.items()).extracting(TopupDto::topupId).containsExactly(first);
    }

    @Test
    @DisplayName("결과가 정해지지 않은 충전 건(READY)이 있으면 true, 실패로 끝나면 false다 (다른 회원 충전 건은 보지 않음)")
    void hasPendingTopups() {
        wallets.add(walletRepository.save(Wallet.create(memberId)));
        wallets.add(walletRepository.save(Wallet.create(otherMemberId)));
        assertThat(paymentFacade.hasPendingTopups(memberId)).isFalse();

        Long topupId = paymentFacade.prepareTopup(memberId, 10_000).topupId();
        paymentFacade.prepareTopup(otherMemberId, 20_000);
        assertThat(paymentFacade.hasPendingTopups(memberId)).isTrue();

        paymentFacade.failTopup(memberId, topupId, "PAY_PROCESS_CANCELED", "사용자 취소");
        assertThat(paymentFacade.hasPendingTopups(memberId)).isFalse();
    }

    @Test
    @DisplayName("내 충전 건은 상세 조회되고, 남의 충전 건은 상세 조회·실패 기록 모두 404")
    void otherMembersTopup() {
        wallets.add(walletRepository.save(Wallet.create(memberId)));
        wallets.add(walletRepository.save(Wallet.create(otherMemberId)));
        Long othersTopupId = paymentFacade.prepareTopup(otherMemberId, 20_000).topupId();

        assertThat(paymentFacade.getTopup(otherMemberId, othersTopupId).topupId()).isEqualTo(othersTopupId);
        assertThatThrownBy(() -> paymentFacade.getTopup(memberId, othersTopupId))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
        assertThatThrownBy(() -> paymentFacade.failTopup(memberId, othersTopupId, "PAY_PROCESS_CANCELED", "취소"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }

    // 다른 테스트·시드와 겹치지 않는 회원 ID (지갑의 member_id 유니크). 회원은 회원 서비스에 있어 ID만 정한다
    private static Long randomMemberId() {
        return ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);
    }
}
