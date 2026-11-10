package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 쿠폰 예약 동시성 (수량 초과 사용, 1인 1회)
 * 잠금과 유니크 인덱스가 실제로 동작하는지 보려면 요청마다 따로 커밋돼야 하므로 @Transactional을 붙이지 않는다.
 * 대신 테스트가 만든 데이터는 @AfterEach에서 직접 지운다.
 */
@SpringBootTest
class CouponReserveConcurrencyTest {

    private static final long ORDER_ID_BASE = 9_100_000L;
    private static final long MEMBER_ID_BASE = 9_100_000L;
    private static final int THREADS = 8;   // DB 연결 풀(10)보다 적게

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private final List<Long> couponIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            couponUsageRepository.deleteAll(couponUsageRepository.findAll().stream()
                    .filter(usage -> couponIds.contains(usage.getCoupon().getId()))
                    .toList());
            couponIds.forEach(couponRepository::deleteById);
        });
        couponIds.clear();
    }

    private Long saveCoupon(Integer totalQuantity) {
        Long id = transactionTemplate.execute(status ->
                couponRepository.save(CouponFixtures.activeCartCoupon(totalQuantity)).getId());
        couponIds.add(id);
        return id;
    }

    @Test
    @DisplayName("10장 쿠폰에 30명이 동시에 예약하면 10명만 성공하고, 나머지는 409-COUPON_SOLD_OUT이며 남은 수량은 0이다")
    void limitedQuantity() throws Exception {
        Long couponId = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();

        Map<String, AtomicInteger> results = runConcurrently(30, i ->
                () -> couponFacade.reserveCartCoupon(MEMBER_ID_BASE + i, couponId, ORDER_ID_BASE + i, 50_000, now));

        assertThat(count(results, "SUCCESS")).isEqualTo(10);
        assertThat(count(results, "409-COUPON_SOLD_OUT")).isEqualTo(20);
        assertThat(remaining(couponId)).isZero();
        assertThat(usageCount(couponId, CouponUsageStatus.RESERVED)).isEqualTo(10);
    }

    @Test
    @DisplayName("수량 제한 없는 쿠폰을 같은 회원의 주문 5건이 동시에 예약하면 1건만 성공하고 나머지는 409-COUPON_ALREADY_USED다")
    void sameMemberUnlimited() throws Exception {
        Long couponId = saveCoupon(null);
        LocalDateTime now = LocalDateTime.now();

        Map<String, AtomicInteger> results = runConcurrently(5, i ->
                () -> couponFacade.reserveCartCoupon(MEMBER_ID_BASE, couponId, ORDER_ID_BASE + 100 + i, 50_000, now));

        assertThat(count(results, "SUCCESS")).isEqualTo(1);
        assertThat(count(results, "409-COUPON_ALREADY_USED")).isEqualTo(4);
        assertThat(usageCount(couponId, CouponUsageStatus.RESERVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("수량 제한 쿠폰을 같은 회원이 동시에 예약해 실패한 요청은 줄였던 수량도 롤백돼, 남은 수량은 1장만 줄어든다")
    void sameMemberLimited() throws Exception {
        Long couponId = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();

        Map<String, AtomicInteger> results = runConcurrently(5, i ->
                () -> couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 1, couponId, ORDER_ID_BASE + 200 + i, 50_000, now));

        assertThat(count(results, "SUCCESS")).isEqualTo(1);
        assertThat(count(results, "409-COUPON_ALREADY_USED")).isEqualTo(4);
        assertThat(remaining(couponId)).isEqualTo(9);
    }

    @Test
    @DisplayName("예약 10건과 해제가 섞여도 남은 수량은 (총 수량 - 남은 예약 수)와 같다")
    void reserveAndRelease() throws Exception {
        Long couponId = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 300 + i, couponId, ORDER_ID_BASE + 300 + i, 50_000, now);
        }

        // 앞의 5건은 해제하고, 새로 5명이 동시에 예약한다
        Map<String, AtomicInteger> results = runConcurrently(10, i -> i < 5
                ? () -> couponFacade.releaseReservedCoupons(ORDER_ID_BASE + 300 + i, now)
                : () -> couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 400 + i, couponId, ORDER_ID_BASE + 400 + i, 50_000, now));

        assertThat(count(results, "SUCCESS")).isEqualTo(10);
        assertThat(usageCount(couponId, CouponUsageStatus.RESERVED)).isEqualTo(5);
        assertThat(usageCount(couponId, CouponUsageStatus.RELEASED)).isEqualTo(5);
        assertThat(remaining(couponId)).isEqualTo(5);
    }

    @Test
    @DisplayName("같은 주문의 예약 해제가 동시에 여러 번 와도(취소·만료) 해제는 한 번만 되고 남은 수량은 1장만 돌아온다")
    void releaseSameOrderConcurrently() throws Exception {
        Long couponId = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();
        long orderId = ORDER_ID_BASE + 500;
        couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 500, couponId, orderId, 50_000, now);
        AtomicInteger releasedTotal = new AtomicInteger();

        Map<String, AtomicInteger> results = runConcurrently(THREADS, i ->
                () -> releasedTotal.addAndGet(couponFacade.releaseReservedCoupons(orderId, now)));

        assertThat(count(results, "SUCCESS")).isEqualTo(THREADS);
        assertThat(releasedTotal.get()).isEqualTo(1);   // 처리했다고 답한 요청은 하나뿐
        assertThat(usageCount(couponId, CouponUsageStatus.RELEASED)).isEqualTo(1);
        assertThat(remaining(couponId)).isEqualTo(10);
    }

    @Test
    @DisplayName("결제 성공과 예약 해제가 동시에 와도 한쪽만 반영된다: USED면 수량이 그대로, RELEASED면 1장 돌아온다")
    void useAndReleaseConcurrently() throws Exception {
        Long couponId = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();
        int orders = THREADS / 2;
        for (int i = 0; i < orders; i++) {
            couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 600 + i, couponId, ORDER_ID_BASE + 600 + i, 50_000, now);
        }

        // 주문마다 결제 성공 1번과 해제 1번을 동시에 보낸다
        runConcurrently(orders * 2, i -> i % 2 == 0
                ? () -> couponFacade.useReservedCoupons(ORDER_ID_BASE + 600 + i / 2, now)
                : () -> couponFacade.releaseReservedCoupons(ORDER_ID_BASE + 600 + i / 2, now));

        long used = usageCount(couponId, CouponUsageStatus.USED);
        long released = usageCount(couponId, CouponUsageStatus.RELEASED);
        assertThat(used + released).isEqualTo(orders);   // 기록마다 둘 중 하나로만 끝난다
        assertThat(usageCount(couponId, CouponUsageStatus.RESERVED)).isZero();
        assertThat(remaining(couponId)).isEqualTo(10 - used);   // 해제된 수만큼만 돌아온다
    }

    @Test
    @DisplayName("한 주문의 장바구니 쿠폰과 상품 쿠폰 중 상품 쿠폰이 중지되면 결제 때 409-COUPON_NOT_ACTIVE이고, 먼저 사용 처리한 장바구니 쿠폰도 함께 롤백돼 둘 다 RESERVED로 남는다")
    void useRollsBackWhenOneCouponStopped() {
        Long activeCouponId = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();
        long orderId = ORDER_ID_BASE + 700;
        couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 700, activeCouponId, orderId, 50_000, now);
        // 상품 쿠폰 예약은 주문 연동 이슈에서 생기므로, 같은 주문에 상품 쿠폰 예약 기록을 직접 만든다
        Long stoppedCouponId = transactionTemplate.execute(status -> {
            Coupon productCoupon = couponRepository.save(CouponFixtures.productCouponBase(now.minusDays(1), now.plusDays(1), null));
            couponUsageRepository.save(CouponUsage.reserve(productCoupon, MEMBER_ID_BASE + 700, orderId, 1L, 3_000, now));
            return productCoupon.getId();
        });
        couponIds.add(stoppedCouponId);
        couponFacade.stopCouponByAdmin(stoppedCouponId, "부정 사용");

        assertThatThrownBy(() -> couponFacade.useReservedCoupons(orderId, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");

        assertThat(usageCount(activeCouponId, CouponUsageStatus.RESERVED)).isEqualTo(1);
        assertThat(usageCount(stoppedCouponId, CouponUsageStatus.RESERVED)).isEqualTo(1);
        assertThat(usageCount(activeCouponId, CouponUsageStatus.USED)).isZero();
    }

    @Test
    @DisplayName("같은 주문에 서로 다른 장바구니 쿠폰을 동시에 예약하면 1건만 성공하고 나머지는 409-COUPON_ORDER_LIMIT_EXCEEDED, 줄였던 수량도 롤백된다")
    void oneCartCouponPerOrderConcurrently() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(saveCoupon(10));
        LocalDateTime now = LocalDateTime.now();
        long orderId = ORDER_ID_BASE + 800;

        Map<String, AtomicInteger> results = runConcurrently(ids.size(), i ->
                () -> couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 800, ids.get(i), orderId, 50_000, now));

        assertThat(count(results, "SUCCESS")).isEqualTo(1);
        assertThat(count(results, "409-COUPON_ORDER_LIMIT_EXCEEDED")).isEqualTo(ids.size() - 1);
        assertThat(ids.stream().mapToInt(this::remaining).sum()).isEqualTo(10 * ids.size() - 1);   // 성공한 1장만 줄었다
    }

    @Test
    @DisplayName("주문의 장바구니 쿠폰 예약을 해제하면 같은 주문에 다른 장바구니 쿠폰을 다시 고를 수 있다")
    void reselectCartCouponAfterRelease() {
        Long first = saveCoupon(10);
        Long second = saveCoupon(10);
        LocalDateTime now = LocalDateTime.now();
        long orderId = ORDER_ID_BASE + 900;
        couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 900, first, orderId, 50_000, now);

        assertThatThrownBy(() -> couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 900, second, orderId, 50_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_ORDER_LIMIT_EXCEEDED");

        couponFacade.releaseReservedCoupons(orderId, now);
        couponFacade.reserveCartCoupon(MEMBER_ID_BASE + 900, second, orderId, 50_000, now);
        assertThat(usageCount(second, CouponUsageStatus.RESERVED)).isEqualTo(1);
    }

    /**
     * 작업을 동시에 시작하고, 결과를 "SUCCESS" 또는 실패한 resultCode별로 센다.
     * DomainException이 아닌 예외가 나면 테스트를 실패시킨다.
     */
    private Map<String, AtomicInteger> runConcurrently(int count, IntFunction<Runnable> taskFactory) throws Exception {
        Map<String, AtomicInteger> results = new ConcurrentHashMap<>();
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < count; i++) {
                Runnable task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    ready.await();
                    try {
                        task.run();
                        results.computeIfAbsent("SUCCESS", key -> new AtomicInteger()).incrementAndGet();
                    } catch (DomainException e) {
                        results.computeIfAbsent(e.getResultCode(), key -> new AtomicInteger()).incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        return results;
    }

    private int count(Map<String, AtomicInteger> results, String key) {
        AtomicInteger value = results.get(key);
        return value == null ? 0 : value.get();
    }

    private Integer remaining(Long couponId) {
        return transactionTemplate.execute(status -> couponRepository.findById(couponId).orElseThrow().getRemainingQuantity());
    }

    private long usageCount(Long couponId, CouponUsageStatus usageStatus) {
        return transactionTemplate.execute(status -> couponUsageRepository.findAll().stream()
                .filter(usage -> usage.getCoupon().getId().equals(couponId))
                .map(CouponUsage::getStatus)
                .filter(usageStatus::equals)
                .count());
    }
}
