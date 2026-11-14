package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 주문 항목 정가·할인율을 실제 DB(로컬 Docker PostgreSQL, V14)에 저장하고 주문 상세에 그대로 내주는지 확인한다 (TRSS-222, #316 후속).
 * 상품·회원·결제는 다른 서비스(또는 다른 컨텍스트)라 목으로 둔다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderListPriceIntegrationTest {

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;
    @MockitoBean private ProductApi productApi;

    private final long buyerId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final long optionId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> {
            orderRepository.deleteAllById(orderIds);
            cartRepository.findByMemberId(buyerId).ifPresent(cartRepository::delete);
        });
    }

    private OptionSnapshotDto option(long price, long listPrice, Integer discountRate) {
        return new OptionSnapshotDto(optionId, 10L, 2L, "립스틱", "대용량", price, listPrice, discountRate, 10, true, null, null);
    }

    /** 장바구니에 옵션 하나(quantity개)를 담고 상품·판매자 조회를 목으로 둔다. 장바구니 항목 ID를 돌려준다 */
    private Long givenCartItem(OptionSnapshotDto option, int quantity) {
        given(productApi.getOptions(anyCollection())).willReturn(List.of(option));
        given(memberApi.getSellers(any())).willReturn(List.of(new SellerDto(2L, 20L, "테스트상점", SellerStatus.APPROVED)));

        return transactionTemplate.execute(status -> {
            Cart cart = cartRepository.save(Cart.create(buyerId));
            cart.addItem(optionId, quantity, 99);
            cartRepository.flush();
            return cart.getItems().getFirst().getId();
        });
    }

    private Map<String, Object> itemRow(Long orderId) {
        return jdbcTemplate.queryForMap(
                "select unit_price, list_unit_price, discount_rate, line_amount from market_order_item where order_id = ?",
                orderId);
    }

    @Test
    @DisplayName("할인 중(옵션 추가금 있음) 상품: list_unit_price·discount_rate를 스냅샷 값으로 저장하고 주문 상세에 그대로 낸다. "
            + "주문 뒤 할인이 끝나도 주문 금액·결제 요청 금액은 생성 시점 그대로다")
    void discounted_savedAndFixedAtCreation() {
        // 상품 10,000원 20% 할인 → 8,000원 + 옵션 추가금 5,000원 = 13,000원 x 3 = 39,000원 (정가 15,000원)
        Long cartItemId = givenCartItem(option(13_000, 15_000, 20), 3);

        OrderCreateResponse created = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 39_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(created.orderId());

        Map<String, Object> row = itemRow(created.orderId());
        assertThat(row.get("unit_price")).isEqualTo(13_000L);
        assertThat(row.get("list_unit_price")).isEqualTo(15_000L);
        assertThat(row.get("discount_rate")).isEqualTo(20);
        assertThat(row.get("line_amount")).isEqualTo(39_000L);

        // 주문 뒤 할인이 끝남: 상품 모듈은 이제 정가를 돌려준다
        given(productApi.getOptions(anyCollection())).willReturn(List.of(option(15_000, 15_000, null)));

        OrderDetailResponse detail = orderFacade.getOrderDetail(buyerId, created.orderId());
        OrderItemResponse item = detail.items().get(0);
        assertThat(item.unitPrice()).isEqualTo(13_000);
        assertThat(item.listPrice()).isEqualTo(15_000);
        assertThat(item.discountRate()).isEqualTo(20);
        assertThat(detail.totalPrice()).isEqualTo(39_000);
        assertThat(detail.payAmount()).isEqualTo(39_000);

        given(paymentApi.pay(any())).willReturn(new PayResultDto(901L, PayResultStatus.PAID, 0, 0));
        orderFacade.payOrder(buyerId, created.orderId());

        ArgumentCaptor<PayCommand> command = ArgumentCaptor.forClass(PayCommand.class);
        verify(paymentApi).pay(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(39_000);   // 정가(45,000원) 기준으로 바뀌지 않는다
    }

    @Test
    @DisplayName("할인 없는 상품: list_unit_price = unit_price, discount_rate = null")
    void notDiscounted() {
        Long cartItemId = givenCartItem(option(30_000, 30_000, null), 1);

        OrderCreateResponse created = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 30_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(created.orderId());

        Map<String, Object> row = itemRow(created.orderId());
        assertThat(row.get("list_unit_price")).isEqualTo(row.get("unit_price")).isEqualTo(30_000L);
        assertThat(row.get("discount_rate")).isNull();

        OrderItemResponse item = orderFacade.getOrderDetail(buyerId, created.orderId()).items().get(0);
        assertThat(item.listPrice()).isEqualTo(30_000);
        assertThat(item.discountRate()).isNull();
    }
}
