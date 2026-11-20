package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.boundedContext.order.in.dto.PriceChangedResponse;
import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [QA] TRSS-210 배송비: 실제 DB(로컬 Docker PostgreSQL)에서 V10 스키마, 쿠폰 없는 주문의 PRICE_CHANGED 롤백,
 * 같은 키 재요청, 주문 상세·목록 응답, V10 이전 주문(shipping_fee=0)의 결제 요청을 확인한다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderShippingFeeQaIntegrationTest {

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;
    @MockitoBean private ProductApi productApi;

    private final long buyerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final long optionId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
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

    private Long givenCartItem(long price) {
        given(productApi.getOptions(anyCollection())).willReturn(List.of(
                new OptionSnapshotDto(optionId, 10L, 2L, "립스틱", "기본", price, price, null, 10, true, null, null)));
        given(memberApi.getSellers(any())).willReturn(List.of(new SellerDto(2L, 20L, "테스트상점", SellerStatus.APPROVED)));

        return transactionTemplate.execute(status -> {
            Cart cart = cartRepository.save(Cart.create(buyerId));
            cart.addItem(optionId, 1, 99);
            cartRepository.flush();
            return cart.getItems().getFirst().getId();
        });
    }

    private long countBuyerOrders() {
        Long count = jdbcTemplate.queryForObject("select count(*) from market_order where buyer_id = ?", Long.class, buyerId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("V10: market_order.shipping_fee는 bigint not null default 0이고 CHECK 제약이 없다")
    void v10_schema() {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                select data_type, is_nullable, column_default
                from information_schema.columns
                where table_schema = 'market' and table_name = 'market_order' and column_name = 'shipping_fee'
                """);
        assertThat(column.get("data_type")).isEqualTo("bigint");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default"))).startsWith("0");

        Long checks = jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'market.market_order'::regclass and contype = 'c'
                  and pg_get_constraintdef(oid) like '%shipping_fee%'
                """, Long.class);
        assertThat(checks).isZero();
    }

    @Test
    @DisplayName("V10: shipping_fee 없이 넣은 행(V10 이전 주문 흉내)은 0이 되고 pay_amount는 그대로다")
    void v10_defaultForRowsWithoutShippingFee() {
        Long cartItemId = givenCartItem(20_000);
        OrderCreateResponse created = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 20_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(created.orderId());

        // 컬럼을 빼고 넣은 행과 같은 결과가 되도록 기본값으로 되돌리고, pay_amount는 V8 시절 식으로 둔다
        jdbcTemplate.update("update market_order set shipping_fee = default, pay_amount = total_price where id = ?",
                created.orderId());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select shipping_fee, pay_amount from market_order where id = ?", created.orderId());
        assertThat(row.get("shipping_fee")).isEqualTo(0L);
        assertThat(row.get("pay_amount")).isEqualTo(20_000L);

        // 배포 후 확인 쿼리: 이 주문은 식과 어긋나지 않는다
        Long mismatch = jdbcTemplate.queryForObject("""
                select count(*) from market_order
                where id = ? and pay_amount <> total_price - product_discount_amount - cart_coupon_discount_amount + shipping_fee
                """, Long.class, created.orderId());
        assertThat(mismatch).isZero();
    }

    @Test
    @DisplayName("쿠폰 없음: 배송비를 더한 금액(장바구니 expectedPayAmount)을 보내면 409-PRICE_CHANGED, currentAmount는 상품 금액 합계, 주문·재고 변경 없음")
    void noCoupon_sendingPayAmountIsPriceChanged_andNothingSaved() {
        Long cartItemId = givenCartItem(20_000);

        assertThatThrownBy(() -> orderFacade.createOrder(buyerId, null, List.of(cartItemId), 22_500, null,
                OrderCouponSelection.NONE))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-PRICE_CHANGED")
                .hasFieldOrPropertyWithValue("data", new PriceChangedResponse(20_000));

        assertThat(countBuyerOrders()).isZero();
        verify(productApi, never()).deductStock(any());
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 주문하면 처음 주문의 배송비·결제 금액을 그대로 돌려주고 행은 하나다")
    void sameIdempotencyKey_returnsSameShippingFee() {
        Long cartItemId = givenCartItem(20_000);
        String key = "qa-210-" + buyerId;

        OrderCreateResponse first = orderFacade.createOrder(buyerId, key, List.of(cartItemId), 20_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(first.orderId());
        OrderCreateResponse retry = orderFacade.createOrder(buyerId, key, List.of(cartItemId), 20_000, null,
                OrderCouponSelection.NONE);

        assertThat(retry.orderId()).isEqualTo(first.orderId());
        assertThat(retry.shippingFee()).isEqualTo(2_500);
        assertThat(retry.payAmount()).isEqualTo(22_500);
        assertThat(countBuyerOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("주문 상세는 shippingFee와 배송비 포함 payAmount, 목록은 payAmount(= totalPrice 호환 필드)를 준다")
    void detailAndList_showShippingFee() {
        Long cartItemId = givenCartItem(20_000);
        OrderCreateResponse created = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 20_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(created.orderId());

        OrderDetailResponse detail = orderFacade.getOrderDetail(buyerId, created.orderId());
        assertThat(detail.totalPrice()).isEqualTo(20_000);
        assertThat(detail.shippingFee()).isEqualTo(2_500);
        assertThat(detail.payAmount()).isEqualTo(22_500);

        PageResponse<OrderSummaryResponse> list = orderFacade.getOrders(buyerId, null, null, null, PageRequest.of(0, 10));
        assertThat(list.items()).hasSize(1);
        assertThat(list.items().getFirst().payAmount()).isEqualTo(22_500);
        assertThat(list.items().getFirst().totalPrice()).isEqualTo(22_500);
    }

    @Test
    @DisplayName("V10 이전 주문(shipping_fee=0, pay_amount=상품 합계)을 결제하면 배송비 0, 금액은 저장된 pay_amount로 보낸다 (다시 계산하지 않음)")
    void legacyOrder_paysWithoutShippingFee() {
        Long cartItemId = givenCartItem(20_000);
        OrderCreateResponse created = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 20_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(created.orderId());
        jdbcTemplate.update("update market_order set shipping_fee = 0, pay_amount = total_price where id = ?",
                created.orderId());

        OrderDetailResponse detail = orderFacade.getOrderDetail(buyerId, created.orderId());
        assertThat(detail.shippingFee()).isZero();
        assertThat(detail.payAmount()).isEqualTo(20_000);

        given(productApi.getOptions(anyCollection())).willReturn(List.of(
                new OptionSnapshotDto(optionId, 10L, 2L, "립스틱", "기본", 20_000, 20_000, null, 10, true, null, null)));
        given(paymentApi.pay(any())).willReturn(new PayResultDto(902L, PayResultStatus.PAID, 0, 0));
        orderFacade.payOrder(buyerId, created.orderId());

        ArgumentCaptor<PayCommand> command = ArgumentCaptor.forClass(PayCommand.class);
        verify(paymentApi).pay(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(20_000);
        assertThat(command.getValue().shippingFee()).isZero();
        assertThat(command.getValue().couponSubsidyAmount()).isZero();
    }
}
