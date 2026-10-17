package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.payment.app.dto.AdminPaymentDto;
import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.page.PageResponse;

/**
 * 관리자 결제 조회 결제 탭을 실제 DB로 확인한다 (이메일·보관 금액 상태 채우기, 검색어·기간).
 * 다른 데이터와 섞이지 않도록 이번 테스트만의 회원 이메일로 검색한다. 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class AdminPaymentQueryIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    private final String email = "admin-pay-" + UUID.randomUUID().toString().substring(0, 8) + "@palette.test";

    private Member member;
    private Wallet wallet;
    private final List<Payment> payments = new ArrayList<>();
    private final List<WalletHolding> holdings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.signUp(email, "encoded", "결제조회"));
        wallet = walletRepository.save(Wallet.create(member.getId()));
    }

    @AfterEach
    void cleanUp() {
        walletHoldingRepository.deleteAll(holdings);
        paymentRepository.deleteAll(payments);
        walletRepository.delete(wallet);
        memberRepository.delete(member);
    }

    @Test
    @DisplayName("이메일로 검색하면 그 회원의 결제 시도가 최신순으로 나오고, 이메일·보관 금액 상태가 채워진다")
    void searchByEmail() {
        Payment paid = savePaid(72_900);
        Payment failed = saveFailed(52_000);

        PageResponse<AdminPaymentDto> result = paymentFacade.getAdminPayments(email, null, null, PageRequest.of(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items())
                .extracting(AdminPaymentDto::paymentId, AdminPaymentDto::memberEmail, AdminPaymentDto::status, AdminPaymentDto::holdingStatus)
                .containsExactly(
                        tuple(failed.getId(), email, PaymentStatus.FAILED_INSUFFICIENT_BALANCE, null),
                        tuple(paid.getId(), email, PaymentStatus.PAID, WalletHoldingStatus.HELD));
    }

    @Test
    @DisplayName("기간은 양 끝 날짜를 포함하고, 기간 밖의 결제는 빠진다")
    void filterByPeriod() {
        savePaid(10_000);
        LocalDate today = LocalDate.now();

        PageResponse<AdminPaymentDto> todayOnly = paymentFacade.getAdminPayments(email, today, today, PageRequest.of(0, 20));
        PageResponse<AdminPaymentDto> untilYesterday = paymentFacade.getAdminPayments(email, null, today.minusDays(1), PageRequest.of(0, 20));

        assertThat(todayOnly.totalElements()).isEqualTo(1);
        assertThat(untilYesterday.items()).isEmpty();
    }

    @Test
    @DisplayName("없는 이메일이나 주문번호로 검색하면 0건")
    void noMatch() {
        savePaid(10_000);

        PageResponse<AdminPaymentDto> byUnknownEmail = paymentFacade.getAdminPayments("nobody-" + email, null, null, PageRequest.of(0, 20));
        PageResponse<AdminPaymentDto> byOrderNumber = paymentFacade.getAdminPayments("ORD-20261009-0142", null, null, PageRequest.of(0, 20));

        assertThat(byUnknownEmail.totalElements()).isZero();
        assertThat(byOrderNumber.totalElements()).isZero();
    }

    private Payment savePaid(long amount) {
        Payment payment = paymentRepository.save(Payment.paid(randomOrderId(), wallet, amount, "TEST-" + UUID.randomUUID(), LocalDateTime.now()));
        payments.add(payment);
        holdings.add(walletHoldingRepository.save(WalletHolding.hold(payment, LocalDateTime.now())));
        return payment;
    }

    private Payment saveFailed(long amount) {
        Payment payment = paymentRepository.save(Payment.failed(randomOrderId(), wallet, amount, "TEST-" + UUID.randomUUID(),
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족"));
        payments.add(payment);
        return payment;
    }

    // 다른 테스트·시드 주문과 겹치지 않는 주문 ID (홀딩의 order_id 유니크)
    private Long randomOrderId() {
        return -ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
