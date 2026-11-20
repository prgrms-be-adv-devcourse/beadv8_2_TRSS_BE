package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.CartOptionDto;
import com.backend.shared.order.dto.PurchasedProductDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.out.OrderApi;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * 상품 판매량 집계(OrderApi): 결제 시각 기준 최근 판매량에서 취소·반품과 집계 구간 밖 주문을 빼고,
 * 판매량이 바뀐 상품(결제·취소·반품, 구간 밖으로 나간 결제)을 찾는다. 다른 데이터와 섞이지 않게 큰 상품 ID를 쓰고 롤백한다.
 */
@SpringBootTest
@Transactional
class OrderSalesStatsTest {

    private static final long BUYER_ID = 9_910L;
    private static final long PRODUCT_A = 9_910_001L;
    private static final long PRODUCT_B = 9_910_002L;
    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private OrderApi orderApi;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private EntityManager em;

    /** 쓰지 않지만 다른 주문 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @Test
    @DisplayName("판매량: 결제한 수량을 상품별로 더하고, 결제 전·취소·반품·집계 구간 밖 주문은 뺀다. 구매확정 전(결제 완료·반품 요청)도 센다")
    void salesQuantities() {
        paid(PRODUCT_A, 1, NOW.minusDays(1), null);                                 // 1
        paid(PRODUCT_A, 2, NOW.minusDays(2), null);                                 // 2
        paid(PRODUCT_A, 3, NOW.minusDays(3), OrderStatus.RETURN_REQUESTED);         // 3 (반품 요청 중은 센다)
        notPaid(PRODUCT_A, 4);                                                      // 결제 전
        paid(PRODUCT_A, 5, NOW.minusDays(1), OrderStatus.CANCELED);                 // 결제 뒤 취소
        paid(PRODUCT_A, 6, NOW.minusDays(1), OrderStatus.RETURNED);                 // 반품
        paid(PRODUCT_A, 7, NOW.minusDays(40), null);                                // 구간 밖
        paid(PRODUCT_B, 8, NOW.minusDays(1), null);

        assertThat(orderApi.getSalesQuantities(List.of(PRODUCT_A, PRODUCT_B, 9_910_999L), NOW.minusDays(30)))
                .containsOnly(entry(PRODUCT_A, 6L), entry(PRODUCT_B, 8L));
    }

    @Test
    @DisplayName("판매량이 바뀐 상품: 구간 안에 결제·취소·반품으로 상태가 바뀐 주문의 상품만 찾는다")
    void productIdsWithSalesChanged() {
        paid(PRODUCT_A, 1, NOW, null);   // 지금 결제 → 상태 이력 PAID
        notPaid(PRODUCT_B, 1);           // 결제 전 (결제 상태 이력 없음)

        assertThat(orderApi.findProductIdsWithSalesChanged(NOW.minusMinutes(1), NOW.plusMinutes(1)))
                .contains(PRODUCT_A)
                .doesNotContain(PRODUCT_B);
    }

    @Test
    @DisplayName("집계 구간 밖으로 나가는 판매: 결제 시각이 (from, to]인 판매량 주문의 상품을 찾는다")
    void productIdsPaidBetween() {
        paid(PRODUCT_A, 1, NOW.minusDays(30).minusSeconds(30), null);   // 방금 30일이 지났다
        paid(PRODUCT_B, 1, NOW.minusDays(31), null);                    // 이미 지난 구간

        assertThat(orderApi.findProductIdsPaidBetween(NOW.minusDays(30).minusMinutes(1), NOW.minusDays(30)))
                .contains(PRODUCT_A)
                .doesNotContain(PRODUCT_B);
    }

    @Test
    @DisplayName("구매자가 결제한 상품: 기간 안에 판매량에 세는 상태로 결제한 상품만, 같은 상품은 가장 최근 결제 시각 하나로 최근 순")
    void recentPurchases() {
        paid(PRODUCT_A, 1, NOW.minusDays(10), null);
        paid(PRODUCT_A, 1, NOW.minusDays(2), OrderStatus.CONFIRMED);   // 같은 상품의 더 최근 결제
        paid(PRODUCT_B, 1, NOW.minusDays(5), null);
        paid(9_910_003L, 1, NOW.minusDays(1), OrderStatus.CANCELED);    // 결제 뒤 취소
        paid(9_910_004L, 1, NOW.minusDays(200), null);                  // 기간 밖
        notPaid(9_910_005L, 1);                                          // 결제 전

        assertThat(orderApi.getRecentPurchases(BUYER_ID, NOW.minusDays(180), 10))
                .containsExactly(new PurchasedProductDto(PRODUCT_A, NOW.minusDays(2)), new PurchasedProductDto(PRODUCT_B, NOW.minusDays(5)));
        assertThat(orderApi.getRecentPurchases(BUYER_ID, NOW.minusDays(180), 1)).hasSize(1);
        assertThat(orderApi.getRecentPurchases(9_919L, NOW.minusDays(180), 10)).isEmpty();   // 다른 구매자
    }

    @Test
    @DisplayName("구매자 장바구니의 옵션: 담은 옵션과 시각을 최근 순으로, 장바구니가 없으면 빈 목록")
    void cartOptions() {
        Cart cart = Cart.create(BUYER_ID);
        cart.addItem(9_910_011L, 1, 99);
        cart.addItem(9_910_012L, 2, 99);
        cartRepository.save(cart);
        em.flush();

        assertThat(orderApi.getCartOptions(BUYER_ID)).extracting(CartOptionDto::optionId)
                .containsExactlyInAnyOrder(9_910_011L, 9_910_012L);
        assertThat(orderApi.getCartOptions(9_919L)).isEmpty();
    }

    /** paidAt에 결제한 주문. status가 있으면 결제 뒤 상태만 바꾼다 (취소·반품 흐름의 다른 조건 없이 집계만 본다) */
    private void paid(long productId, int quantity, LocalDateTime paidAt, OrderStatus status) {
        save(productId, quantity, paidAt.minusMinutes(1), order -> {   // 결제 가능 시간 안에 결제한다
            order.pay(paidAt);
            if (status != null) ReflectionTestUtils.setField(order, "status", status);
        });
    }

    private void notPaid(long productId, int quantity) {
        save(productId, quantity, NOW, order -> { });
    }

    private void save(long productId, int quantity, LocalDateTime createdAt, Consumer<Order> change) {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(null, productId, productId * 10, 7L, "판매량 상품", "기본", "팔레트샵", 10_000L, quantity)
        ), createdAt, 30);
        change.accept(order);
        orderRepository.save(order);
        em.flush();
    }
}
