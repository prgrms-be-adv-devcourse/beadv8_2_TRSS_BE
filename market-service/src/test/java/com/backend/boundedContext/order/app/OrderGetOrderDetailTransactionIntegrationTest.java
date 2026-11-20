package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.boundedContext.product.app.ProductSupport;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.delivery.out.DeliveryApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.dto.OptionSnapshotDto;

/**
 * 주문 상세의 트랜잭션 범위를 실제 트랜잭션 매니저·실제 DB로 확인한다 (TRSS-307)
 * - 주문·항목·상품 이미지는 읽기 전용 트랜잭션 안에서, 결제·배송 HTTP 조회는 트랜잭션 밖에서 한다
 * - 이미지 조회(ProductFacade.getOptions, 호출한 쪽 트랜잭션에 참여)가 실패하면 rollback-only 때문에
 *   UnexpectedRollbackException으로 바뀌지 않고 원래 예외가 그대로 전파된다
 * 상품 DB 조회는 ProductSupport를 목으로 두어 ProductApiAdapter → ProductFacade(@Transactional) 경로는 실제로 지난다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderGetOrderDetailTransactionIntegrationTest {

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private DeliveryApi deliveryApi;
    @MockitoBean private ProductSupport productSupport;

    private final long buyerId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final long optionId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @Test
    @DisplayName("주문·이미지는 읽기 전용 트랜잭션 안에서, 결제·배송 조회는 트랜잭션 밖에서 한다")
    void remoteCallsRunOutsideTransaction() {
        long orderId = savePaidOrderWithAddress();
        Map<String, Boolean> txActive = new ConcurrentHashMap<>();
        given(productSupport.getOptionSnapshots(anyCollection())).willAnswer(invocation -> {
            txActive.put("product", TransactionSynchronizationManager.isActualTransactionActive());
            txActive.put("productReadOnly", TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return List.of(new OptionSnapshotDto(optionId, 10L, 2L, "립스틱", "기본", 10_000, 10_000, null, 5,
                    true, null, "https://img.example/lipstick.jpg"));
        });
        given(paymentApi.getPaymentAttempts(orderId)).willAnswer(invocation -> {
            txActive.put("payment", TransactionSynchronizationManager.isActualTransactionActive());
            LocalDateTime paidAt = LocalDateTime.now();
            return List.of(new PaymentAttemptDto(1L, PaymentAttemptStatus.PAID, 20_000, paidAt, paidAt, null));
        });
        given(deliveryApi.findByOrderId(orderId)).willAnswer(invocation -> {
            txActive.put("delivery", TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(new DeliveryDto(70L, DeliveryStatus.READY, null, null, null, null));
        });

        OrderDetailResponse response = orderFacade.getOrderDetail(buyerId, orderId);

        assertThat(txActive).containsEntry("product", true).containsEntry("productReadOnly", true)
                .containsEntry("payment", false).containsEntry("delivery", false);
        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.items()).extracting(OrderItemResponse::thumbnailUrl)
                .containsExactly("https://img.example/lipstick.jpg");
        assertThat(response.payments()).hasSize(1);
        assertThat(response.paymentUnavailable()).isFalse();
        assertThat(response.delivery().status()).isEqualTo(DeliveryStatus.READY);
        assertThat(response.deliveryUnavailable()).isFalse();
        assertThat(response.shippingAddress().receiverName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("이미지 조회가 실패하면 원래 예외가 그대로 전파된다 (UnexpectedRollbackException 아님). 결제·배송은 부르지 않는다")
    void productLookupFailure_propagatesOriginalException() {
        long orderId = savePaidOrderWithAddress();
        IllegalStateException failure = new IllegalStateException("상품 조회 실패");
        given(productSupport.getOptionSnapshots(anyCollection())).willThrow(failure);

        assertThatThrownBy(() -> orderFacade.getOrderDetail(buyerId, orderId)).isSameAs(failure);
        verifyNoInteractions(paymentApi, deliveryApi);
    }

    @Test
    @DisplayName("결제·배송이 응답하지 못하면 지금처럼 주문 상세는 200, paymentUnavailable·deliveryUnavailable = true")
    void remoteFailures_stillUnavailable() {
        long orderId = savePaidOrderWithAddress();
        given(productSupport.getOptionSnapshots(anyCollection())).willReturn(List.of());
        given(paymentApi.getPaymentAttempts(orderId))
                .willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        given(deliveryApi.findByOrderId(orderId))
                .willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderDetailResponse response = orderFacade.getOrderDetail(buyerId, orderId);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.payments()).isEmpty();
        assertThat(response.paymentUnavailable()).isTrue();
        assertThat(response.delivery()).isNull();
        assertThat(response.deliveryUnavailable()).isTrue();
    }

    private long savePaidOrderWithAddress() {
        LocalDateTime now = LocalDateTime.now();
        Order order = Order.create(buyerId, null, List.of(
                        new OrderItemSpec(null, 10L, optionId, 2L, "립스틱", "기본", "상점", 10_000, 2)),
                ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", null),
                now, 15);
        order.pay(now);
        Long id = transactionTemplate.execute(status -> orderRepository.save(order).getId());
        orderIds.add(id);
        return id;
    }
}
