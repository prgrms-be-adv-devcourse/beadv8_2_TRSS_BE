package com.backend.boundedContext.order.out;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;

/**
 * 자동 구매 확정 대상 조회(findAutoConfirmTargetIds) 쿼리를 실제 DB(로컬 Docker PostgreSQL)에서 확인한다.
 * 배송지가 있는 주문은 배송 완료 시각, 배송지 없는 주문은 결제 시각이 7일 지난 PAID 주문만 대상이다.
 * 다른 테스트·로컬 데이터와 섞이지 않게, 이 테스트가 만든 주문 ID만 골라 본다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderAutoConfirmTargetQueryTest {

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    // 다른 테스트·시드와 겹치지 않는 구매자 ID
    private final long buyerId = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @Test
    @DisplayName("배송지 있음: 미배송 제외·배송 7일 경과 포함·배송 7일 미만 제외 / 배송지 없음: 결제 7일 경과 포함·미만 제외")
    void findAutoConfirmTargetIds() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);   // 경계 비교가 DB 정밀도(마이크로초)에 흔들리지 않게

        long notDelivered = save(true, now.minusDays(10), null);                       // 결제 10일, 미배송 → 제외
        long deliveredLongAgo = save(true, now.minusDays(10), now.minusDays(8));       // 배송 8일 → 포함
        long deliveredRecently = save(true, now.minusDays(10), now.minusDays(3));      // 결제 10일이어도 배송 3일 → 제외
        long noAddressPaidLongAgo = save(false, now.minusDays(8), null);               // 배송지 없음, 결제 8일 → 포함
        long noAddressPaidRecently = save(false, now.minusDays(3), null);              // 배송지 없음, 결제 3일 → 제외
        long deliveredExactly7Days = save(true, now.minusDays(9), now.minusDays(7));   // 경계: 배송 정확히 7일 → 포함

        List<Long> targets = orderFacade.findAutoConfirmTargetIds(now, orderIds.get(0) - 1, 1000).stream()
                .filter(orderIds::contains)
                .toList();

        assertThat(targets).containsExactly(deliveredLongAgo, noAddressPaidLongAgo, deliveredExactly7Days);
        assertThat(targets).doesNotContain(notDelivered, deliveredRecently, noAddressPaidRecently);
    }

    /** paidAt에 결제됐고 deliveredAt(null이면 미배송)에 배송 완료된 PAID 주문을 저장한다 */
    private long save(boolean withAddress, LocalDateTime paidAt, LocalDateTime deliveredAt) {
        Long id = transactionTemplate.execute(status -> {
            Order order = Order.create(buyerId, null,
                    List.of(new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                    withAddress ? ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", null, null) : null,
                    paidAt.minusMinutes(1), 15);
            order.pay(paidAt);
            if (deliveredAt != null) {
                order.markDelivered(deliveredAt);
            }
            return orderRepository.save(order).getId();
        });
        orderIds.add(id);
        return id;
    }
}
