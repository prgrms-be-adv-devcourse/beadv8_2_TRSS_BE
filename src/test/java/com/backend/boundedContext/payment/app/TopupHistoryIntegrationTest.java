package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;

/**
 * 충전 내역 조회·실패 기록을 실제 DB로 확인한다 (회원별 조회, 최신순, 페이징, 남의 충전 건 404).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class TopupHistoryIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final Long otherMemberId = memberId + 1;

    private final List<Wallet> wallets = new ArrayList<>();

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
}
