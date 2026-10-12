package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시에 들어온 입점 신청 중 하나만 저장되는지 확인한다 (member_id·business_number UNIQUE).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class SellerApplyConcurrencyTest {
    private static final int THREADS = 10;

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    private final List<Long> memberIds = new ArrayList<>();
    private final String businessNumber = randomBusinessNumber();

    @AfterEach
    void cleanUp() {
        sellersWithBusinessNumber().forEach(seller -> {
            sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySeller_IdOrderByIdAsc(seller.getId()));
            sellerRepository.delete(seller);
        });
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    @DisplayName("같은 회원이 동시에 10번 신청해도 하나만 저장된다")
    void sameMember_onlyOneSaved() {
        Long memberId = createMember();

        Result result = runConcurrently(i -> () -> memberFacade.applySeller(memberId, request()));

        result.print("같은 회원 동시 신청");
        assertThat(result.success.get()).isEqualTo(1);
        assertThat(result.rejectedByService.get() + result.rejectedByDb.get()).isEqualTo(THREADS - 1);
        assertThat(result.other.get()).isZero();
        assertThat(sellersWithBusinessNumber()).hasSize(1);
    }

    @Test
    @DisplayName("다른 회원 10명이 같은 사업자번호로 동시에 신청해도 하나만 저장된다")
    void sameBusinessNumber_onlyOneSaved() {
        List<Long> applicants = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) applicants.add(createMember());

        Result result = runConcurrently(i -> () -> memberFacade.applySeller(applicants.get(i), request()));

        result.print("같은 사업자번호 동시 신청");
        assertThat(result.success.get()).isEqualTo(1);
        assertThat(result.rejectedByService.get() + result.rejectedByDb.get()).isEqualTo(THREADS - 1);
        assertThat(result.other.get()).isZero();
        assertThat(sellersWithBusinessNumber()).hasSize(1);
    }

    /**
     * 모든 스레드를 준비시킨 뒤 출발 신호로 한꺼번에 실행하고, 결과를 종류별로 센다.
     */
    private Result runConcurrently(IntFunction<Runnable> task) {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Result result = new Result();

        for (int i = 0; i < THREADS; i++) {
            Runnable job = task.apply(i);
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    job.run();
                    result.success.incrementAndGet();
                } catch (DomainException e) {
                    result.rejectedByService.incrementAndGet();   // 먼저 조회에서 막힘 (409-SELLER_ALREADY_APPLIED / BUSINESS_NUMBER_DUPLICATED)
                } catch (DataIntegrityViolationException e) {
                    result.rejectedByDb.incrementAndGet();        // 조회를 통과했지만 INSERT에서 UNIQUE에 막힘
                } catch (Exception e) {
                    result.other.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            ready.await();
            start.countDown();   // 출발
            done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
        return result;
    }

    private Long createMember() {
        String email = "seller-it-" + UUID.randomUUID() + "@palette.com";
        Long id = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        memberIds.add(id);
        return id;
    }

    private SellerApplyRequest request() {
        return new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동", "010-1234-5678",
                "국민은행", "123456-01-123456", "홍길동");
    }

    private List<Seller> sellersWithBusinessNumber() {
        String digits = Seller.digitsOnly(businessNumber);
        return sellerRepository.findAll().stream()
                .filter(seller -> digits.equals(seller.getBusinessNumber()))
                .toList();
    }

    private static String randomBusinessNumber() {
        String digits = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }

    private static class Result {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger rejectedByService = new AtomicInteger();
        final AtomicInteger rejectedByDb = new AtomicInteger();
        final AtomicInteger other = new AtomicInteger();

        void print(String title) {
            System.out.printf("[%s] 성공 %d / 조회에서 막힘 %d / DB UNIQUE에서 막힘 %d / 기타 %d%n",
                    title, success.get(), rejectedByService.get(), rejectedByDb.get(), other.get());
        }
    }
}
