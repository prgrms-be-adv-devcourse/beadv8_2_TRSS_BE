package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.ShippingAddressRequest;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * 배송비를 실제 DB(로컬 Docker PostgreSQL)에 저장하고 결제 요청에 싣는지 확인한다 (TRSS-210).
 * 결제·회원·상품 조회는 다른 서비스(또는 다른 컨텍스트)라 목으로 둔다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class OrderShippingFeeIntegrationTest {

    private static final String RECEIVER = "김배송비수령";
    private static final String PHONE = "01055556666";
    private static final String ADDRESS = "서울 강남구 배송비로 210";

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

    /** 장바구니에 옵션 하나(price원 x 1)를 담고 상품·판매자 조회를 목으로 둔다. 장바구니 항목 ID를 돌려준다 */
    private Long givenCartItem(long price) {
        given(productApi.getOptions(anyCollection())).willReturn(List.of(
                new OptionSnapshotDto(optionId, 10L, 2L, "립스틱", "기본", price, 10, true, null, null)));
        given(memberApi.getSellers(any())).willReturn(List.of(new SellerDto(2L, 20L, "테스트상점", SellerStatus.APPROVED)));

        return transactionTemplate.execute(status -> {
            Cart cart = cartRepository.save(Cart.create(buyerId));
            cart.addItem(optionId, 1, 99);
            cartRepository.flush();
            return cart.getItems().getFirst().getId();
        });
    }

    private Map<String, Object> orderRow(Long orderId) {
        return jdbcTemplate.queryForMap(
                "select total_price, shipping_fee, pay_amount from market_order where id = ?", orderId);
    }

    @Test
    @DisplayName("29,999원 주문: shipping_fee 2,500, pay_amount 32,499로 저장하고, 결제 요청에 배송비를 싣는다. 주문 생성 로그에 배송지·연락처가 없다")
    void belowThreshold_savesShippingFee_andSendsToPayment(CapturedOutput output) {
        Long cartItemId = givenCartItem(29_999);

        OrderCreateResponse response = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 29_999,
                new ShippingAddressRequest(RECEIVER, PHONE, "06236", ADDRESS, "101호", null),
                OrderCouponSelection.NONE);
        orderIds.add(response.orderId());

        assertThat(response.shippingFee()).isEqualTo(2_500);
        Map<String, Object> row = orderRow(response.orderId());
        assertThat(row.get("shipping_fee")).isEqualTo(2_500L);
        assertThat(row.get("pay_amount")).isEqualTo(32_499L);

        // 주문 생성 INFO 로그는 금액 항목만 남긴다
        assertThat(output.getOut()).contains("주문 생성 orderId=" + response.orderId(), "shippingFee=2500", "payAmount=32499");
        assertThat(output.getOut()).doesNotContain(RECEIVER, PHONE, ADDRESS);

        given(paymentApi.pay(any())).willReturn(new PayResultDto(901L, PayResultStatus.PAID, 0, 0));
        orderFacade.payOrder(buyerId, response.orderId());

        ArgumentCaptor<PayCommand> command = ArgumentCaptor.forClass(PayCommand.class);
        verify(paymentApi).pay(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(32_499);
        assertThat(command.getValue().shippingFee()).isEqualTo(2_500);
        assertThat(command.getValue().couponSubsidyAmount()).isZero();   // 쿠폰 반영이 꺼져 있음
    }

    @Test
    @DisplayName("30,000원 주문: shipping_fee 0, pay_amount 30,000으로 저장한다")
    void atThreshold_freeShipping() {
        Long cartItemId = givenCartItem(30_000);

        OrderCreateResponse response = orderFacade.createOrder(buyerId, null, List.of(cartItemId), 30_000, null,
                OrderCouponSelection.NONE);
        orderIds.add(response.orderId());

        Map<String, Object> row = orderRow(response.orderId());
        assertThat(row.get("total_price")).isEqualTo(30_000L);
        assertThat(row.get("shipping_fee")).isEqualTo(0L);
        assertThat(row.get("pay_amount")).isEqualTo(30_000L);
    }
}
