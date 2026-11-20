package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderStatus;

/**
 * [QA] TRSS-249: 관리자 수동 완료의 deliveredAt(관리자가 입력한 과거 시각)이 market에 들어왔을 때 자동 확정 7일 기준.
 * market은 수동 완료와 택배사 완료를 구분하지 않고 같은 DeliveryCompletedEvent → markDelivered로 받는다.
 * - 입력 시각이 7일보다 오래 전이면 다음 자동 확정 회차에 바로 확정된다(구매자 반품 기간 없음 — 정책 확인 필요)
 * - 7일 안이면 입력 시각 + 7일에 확정된다
 * - 같은 주문에 다른 deliveredAt이 또 와도 처음 값을 유지한다
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderManualDeliveredQaIntegrationTest {

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final long buyerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @Test
    @DisplayName("수동 완료 시각이 8일 전이면 기록 직후 자동 확정 대상이고 바로 확정된다")
    void oldManualDeliveredAt_confirmedImmediately() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        long orderId = savePaidOrder(now.minusDays(20));

        orderFacade.markDelivered(orderId, now.minusDays(8));   // 컨슈머가 부르는 경로

        assertThat(targets(now)).contains(orderId);
        assertThat(orderFacade.autoConfirmOrder(orderId, now)).isTrue();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("수동 완료 시각이 2일 전이면 지금은 대상이 아니고, 입력 시각 + 7일에 대상이 된다 (처리 시각이 아니라 입력 시각 기준)")
    void recentManualDeliveredAt_confirmedAfter7DaysFromInput() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime deliveredAt = now.minusDays(2);
        long orderId = savePaidOrder(now.minusDays(10));

        orderFacade.markDelivered(orderId, deliveredAt);

        assertThat(targets(now)).doesNotContain(orderId);
        assertThat(targets(deliveredAt.plusDays(7).minusSeconds(1))).doesNotContain(orderId);
        assertThat(targets(deliveredAt.plusDays(7))).contains(orderId);
    }

    @Test
    @DisplayName("같은 주문에 배송 완료가 두 번 오면(다른 시각) 처음 값을 유지한다")
    void secondDeliveredAt_ignored() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        long orderId = savePaidOrder(now.minusDays(5));

        orderFacade.markDelivered(orderId, now.minusDays(3));
        orderFacade.markDelivered(orderId, now.minusHours(1));

        assertThat(orderRepository.findById(orderId).orElseThrow().getDeliveredAt()).isEqualTo(now.minusDays(3));
    }

    private List<Long> targets(LocalDateTime now) {
        return orderFacade.findAutoConfirmTargetIds(now, orderIds.get(0) - 1, 1000).stream()
                .filter(orderIds::contains)
                .toList();
    }

    /** 배송지가 있고 paidAt에 결제된, 아직 배송 완료 전인 PAID 주문 */
    private long savePaidOrder(LocalDateTime paidAt) {
        Long id = transactionTemplate.execute(status -> {
            Order order = Order.create(buyerId, null,
                    List.of(new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                    ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", null, null),
                    paidAt.minusMinutes(1), 15);
            order.pay(paidAt);
            return orderRepository.save(order).getId();
        });
        orderIds.add(id);
        return id;
    }
}
