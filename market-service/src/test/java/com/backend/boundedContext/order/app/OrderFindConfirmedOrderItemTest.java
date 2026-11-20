package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
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

/**
 * OrderApi.findConfirmedOrderItem을 실제 DB 쿼리로 검증한다.
 * 조건(본인 주문·구매 확정)이 하나라도 안 맞으면 이유와 상관없이 empty여야 한다.
 * 다른 데이터와 섞이지 않도록 이 테스트만 쓰는 구매자 ID로 주문을 만들고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class OrderFindConfirmedOrderItemTest {

    private static final long BUYER_ID = 9_900L;
    private static final long OTHER_BUYER_ID = 9_901L;
    // DB(timestamp, 마이크로초)에 저장했다 읽어도 같은 값이 되도록 초 단위로 자른다
    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private OrderApi orderApi;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager em;

    /** 쓰지 않지만 SellerProductStopFlowIntegrationTest와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @Test
    @DisplayName("본인의 구매 확정 주문 항목이면 항목·상품·옵션 ID, 주문 시점 옵션명, 확정 시각을 돌려준다")
    void confirmed() {
        Order order = save(BUYER_ID, o -> {
            o.pay(NOW);
            o.confirm(ConfirmType.MANUAL, NOW.plusDays(1));
        });
        OrderItem item = order.getItems().get(1);

        ReviewableOrderItemDto result = orderApi.findConfirmedOrderItem(BUYER_ID, item.getId()).orElseThrow();

        assertThat(result).isEqualTo(new ReviewableOrderItemDto(
                item.getId(), 200L, 2_000L, "블루 / L", NOW.plusDays(1)));
    }

    @Test
    @DisplayName("없는 주문 항목이면 empty")
    void notFound() {
        assertThat(orderApi.findConfirmedOrderItem(BUYER_ID, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("다른 회원의 구매 확정 주문 항목이면 empty")
    void otherBuyer() {
        Order order = save(OTHER_BUYER_ID, o -> {
            o.pay(NOW);
            o.confirm(ConfirmType.MANUAL, NOW);
        });

        assertThat(orderApi.findConfirmedOrderItem(BUYER_ID, firstItemId(order))).isEmpty();
    }

    @Test
    @DisplayName("구매 확정 전(결제 대기·결제 실패·결제 완료)이면 empty")
    void notConfirmedYet() {
        Order created = save(BUYER_ID, o -> { });
        Order paymentFailed = save(BUYER_ID, o -> ReflectionTestUtils.setField(o, "status", OrderStatus.PAYMENT_FAILED));
        Order paid = save(BUYER_ID, o -> o.pay(NOW));

        for (Order order : List.of(created, paymentFailed, paid)) {
            assertThat(orderApi.findConfirmedOrderItem(BUYER_ID, firstItemId(order))).isEmpty();
        }
    }

    @Test
    @DisplayName("취소된 주문이면 empty")
    void canceled() {
        Order order = save(BUYER_ID, o -> o.cancel(OrderCancelType.USER, null, NOW));

        assertThat(orderApi.findConfirmedOrderItem(BUYER_ID, firstItemId(order))).isEmpty();
    }

    @Test
    @DisplayName("반품 요청·반품 완료 주문이면 empty")
    void returned() {
        Order returnRequested = save(BUYER_ID, o -> {
            o.pay(NOW);
            ReflectionTestUtils.setField(o, "status", OrderStatus.RETURN_REQUESTED);
        });
        Order returned = save(BUYER_ID, o -> {
            o.pay(NOW);
            ReflectionTestUtils.setField(o, "status", OrderStatus.RETURNED);
        });

        for (Order order : List.of(returnRequested, returned)) {
            assertThat(orderApi.findConfirmedOrderItem(BUYER_ID, firstItemId(order))).isEmpty();
        }
    }

    @Test
    @DisplayName("회원 ID나 주문 항목 ID가 null이면 empty")
    void nullArguments() {
        Order order = save(BUYER_ID, o -> {
            o.pay(NOW);
            o.confirm(ConfirmType.MANUAL, NOW);
        });

        assertThat(orderApi.findConfirmedOrderItem(null, firstItemId(order))).isEmpty();
        assertThat(orderApi.findConfirmedOrderItem(BUYER_ID, null)).isEmpty();
    }

    // 항목 2개짜리 주문을 만들어 상태를 바꾼 뒤 DB에 반영하고 영속성 컨텍스트를 비운다 (조회가 DB 쿼리를 타도록)
    private Order save(long buyerId, Consumer<Order> change) {
        Order order = Order.create(buyerId, List.of(
                new OrderItemSpec(null, 100L, 1_000L, 7L, "티셔츠", "화이트 / M", "팔레트샵", 10_000L, 1),
                new OrderItemSpec(null, 200L, 2_000L, 7L, "셔츠", "블루 / L", "팔레트샵", 20_000L, 2)
        ), NOW, 30);
        change.accept(order);
        orderRepository.save(order);
        em.flush();
        em.clear();
        return order;
    }

    private Long firstItemId(Order order) {
        return order.getItems().getFirst().getId();
    }
}
