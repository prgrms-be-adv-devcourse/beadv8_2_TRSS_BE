package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.domain.ReturnItemDecision;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderReturnedEvent;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 관리자 반품 승인·거절 UseCase: 호출 순서, 재고 복원 대상, 이벤트, 즉시 확정 판단, 로그
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderAdminReturnUseCaseTest {

    private static final long ORDER_ID = 500L;
    private static final long BUYER_ID = 3L;
    private static final long ITEM_1 = 5001L;
    private static final long ITEM_2 = 5002L;
    private static final String RECEIVER = "김반품수령";
    private static final String REJECT_REASON = "관리자 거절 사유 원문";

    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;
    @Mock EventPublisher eventPublisher;
    @Mock OrderPolicy orderPolicy;

    private Order returnRequestedOrder(LocalDateTime deliveredAt) {
        Order order = Order.create(BUYER_ID, null,
                List.of(new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2),
                        new OrderItemSpec(null, 11L, 110L, 2L, "블러셔", "기본", "상점", 5_000, 1)),
                ShippingAddress.of(RECEIVER, "01011112222", "06236", "서울 강남구 반품로 1", "202호", "문 앞"),
                deliveredAt.minusDays(1).minusMinutes(1), 15);
        order.applyShippingFee(2_500);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", ITEM_1);
        ReflectionTestUtils.setField(order.getItems().get(1), "id", ITEM_2);
        order.pay(deliveredAt.minusDays(1));
        order.markDelivered(deliveredAt);
        order.requestReturn(ReturnReasonCode.DEFECTIVE, "깨짐", deliveredAt.plusHours(1));
        return order;
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        OrderApproveReturnUseCase useCase;
        Order order;

        @BeforeEach
        void setUp() {
            useCase = new OrderApproveReturnUseCase(orderRepository, productApi, eventPublisher);
            order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
            given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(10L, 11L));
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        }

        @Test
        @DisplayName("상품 id 조회 → 상품 잠금 → 주문 잠금 → 복원 고른 항목만 RETURN_RESTORE(수량 전체) → OrderReturnedEvent(결제 금액 전액)")
        void approve_order() {
            ReturnResultResponse response = useCase.approve(ORDER_ID,
                    List.of(new ReturnItemDecision(ITEM_1, true), new ReturnItemDecision(ITEM_2, false)));

            assertThat(response).isEqualTo(new ReturnResultResponse(ORDER_ID, OrderStatus.RETURNED, null));
            InOrder inOrder = inOrder(orderRepository, productApi, eventPublisher);
            inOrder.verify(orderRepository).findProductIdsByOrderId(ORDER_ID);
            inOrder.verify(productApi).lockProductsForOrderChange(List.of(10L, 11L));
            inOrder.verify(orderRepository).findByIdForUpdate(ORDER_ID);
            inOrder.verify(productApi).restoreStock(
                    List.of(new StockChangeDto(ORDER_ID, ITEM_1, 100L, 2)), StockRestoreReason.RETURN_RESTORE);
            ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
            inOrder.verify(eventPublisher).publish(event.capture());

            assertThat(event.getValue()).isEqualTo(new OrderReturnedEvent(ORDER_ID, BUYER_ID, 27_500, order.getReturnedAt()));
            assertThat(order.getPayAmount()).isEqualTo(27_500);   // 상품 25,000 + 배송비 2,500
        }

        @Test
        @DisplayName("모두 복원하지 않으면 restoreStock을 부르지 않고(재고 이력 없음) 이벤트만 발행한다")
        void approve_noRestore() {
            useCase.approve(ORDER_ID, List.of(new ReturnItemDecision(ITEM_1, false), new ReturnItemDecision(ITEM_2, false)));

            verify(productApi, never()).restoreStock(anyList(), any());
            verify(eventPublisher).publish(any(OrderReturnedEvent.class));
        }

        @Test
        @DisplayName("없는 주문(항목 없음)이면 404, 상품을 잠그지 않는다")
        void approve_notFound() {
            given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of());

            assertThatThrownBy(() -> useCase.approve(ORDER_ID, List.of()))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("404-ORDER_NOT_FOUND");
            verifyNoInteractions(productApi, eventPublisher);
        }

        @Test
        @DisplayName("항목 불일치면 400, 재고·이벤트 호출 없음")
        void approve_mismatch() {
            assertThatThrownBy(() -> useCase.approve(ORDER_ID, List.of(new ReturnItemDecision(ITEM_1, true))))
                    .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("400-RETURN_ITEMS_MISMATCH");
            verify(productApi, never()).restoreStock(anyList(), any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("상품 쪽이 409 PRODUCT_DELETED를 던지면 그대로 올라가고 이벤트를 발행하지 않는다(트랜잭션 롤백)")
        void approve_productDeleted() {
            willThrow(new DomainException("409-PRODUCT_DELETED", "삭제된 상품입니다.", null))
                    .given(productApi).restoreStock(anyList(), any());

            assertThatThrownBy(() -> useCase.approve(ORDER_ID,
                    List.of(new ReturnItemDecision(ITEM_1, true), new ReturnItemDecision(ITEM_2, true))))
                    .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("409-PRODUCT_DELETED");
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("로그에는 주문 id·항목 수·복원 항목 수·환불 금액만 남고 배송지·사유 원문은 없다")
        void approve_log(CapturedOutput output) {
            useCase.approve(ORDER_ID, List.of(new ReturnItemDecision(ITEM_1, true), new ReturnItemDecision(ITEM_2, false)));

            assertThat(output.getOut())
                    .contains("반품 승인: orderId=500, itemCount=2, restoreItemCount=1, refundAmount=27500")
                    .doesNotContain(RECEIVER).doesNotContain("깨짐");
        }
    }

    @Nested
    @DisplayName("거절")
    class Reject {

        OrderRejectReturnUseCase useCase;
        OrderConfirmSupport orderConfirmSupport;

        @BeforeEach
        void setUp() {
            orderConfirmSupport = new OrderConfirmSupport(eventPublisher);
            useCase = new OrderRejectReturnUseCase(orderRepository, orderPolicy, orderConfirmSupport);
            given(orderPolicy.getAutoConfirmDays()).willReturn(7);
        }

        @Test
        @DisplayName("배송 완료 3일: PAID로 두고 이벤트 없음(confirmType null)")
        void reject_beforeSevenDays() {
            Order order = returnRequestedOrder(LocalDateTime.now().minusDays(3));
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

            ReturnResultResponse response = useCase.reject(ORDER_ID, REJECT_REASON);

            assertThat(response).isEqualTo(new ReturnResultResponse(ORDER_ID, OrderStatus.PAID, null));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("배송 완료 8일: 같은 호출에서 CONFIRMED(RETURN_REJECTED) + OrderConfirmedEvent(정산 후보) 1건")
        void reject_afterSevenDays() {
            Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

            ReturnResultResponse response = useCase.reject(ORDER_ID, REJECT_REASON);

            assertThat(response).isEqualTo(new ReturnResultResponse(ORDER_ID, OrderStatus.CONFIRMED, ConfirmType.RETURN_REJECTED));
            ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
            verify(eventPublisher).publish(event.capture());
            assertThat(event.getValue()).isInstanceOfSatisfying(OrderConfirmedEvent.class, e -> {
                assertThat(e.confirmType()).isEqualTo(ConfirmType.RETURN_REJECTED);
                assertThat(e.items()).hasSize(2);
            });
        }

        @Test
        @DisplayName("없는 주문이면 404")
        void reject_notFound() {
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.reject(ORDER_ID, REJECT_REASON))
                    .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("404-ORDER_NOT_FOUND");
        }

        @Test
        @DisplayName("로그에는 주문 id·즉시 확정 여부만 남고 거절 사유·배송지는 없다")
        void reject_log(CapturedOutput output) {
            Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

            useCase.reject(ORDER_ID, REJECT_REASON);

            assertThat(output.getOut())
                    .contains("반품 거절: orderId=500, immediateConfirm=true")
                    .doesNotContain(REJECT_REASON).doesNotContain(RECEIVER);
        }
    }
}
