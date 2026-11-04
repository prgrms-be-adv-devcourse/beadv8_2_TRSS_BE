package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.PendingOrderConflictResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCreateOrderUseCaseTest {

    @Mock OrderSupport orderSupport;
    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;
    @Mock MemberApi memberApi;   // 설정하지 않으면 findSellerByMemberId는 Optional.empty() = 판매자 아님

    OrderCreateOrderUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Long SELLER_ID = 2L;
    private static final Long ORDER_ID = 500L;

    @BeforeEach
    void setUp() {
        useCase = new OrderCreateOrderUseCase(orderSupport, orderRepository, new OrderPolicy(15, 7, 99, 100, 1), productApi, memberApi);
    }

    @Test
    @DisplayName("탈퇴한 회원이면 장바구니를 잠그지 않고 재고도 차감하지 않으며 401 UNAUTHORIZED (탈퇴 후 남은 Access Token)")
    void withdrawnMember() {
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberApi).checkActiveMember(BUYER_ID);

        assertThatThrownBy(() -> useCase.create(BUYER_ID, null, List.of(1L), 10_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("401-UNAUTHORIZED");

        verifyNoInteractions(orderSupport, orderRepository, productApi);
    }

    /** 옵션 ID = 상품 ID × 10, 판매자는 2번 */
    private OptionSnapshotDto onSale(Long optionId, long price) {
        return new OptionSnapshotDto(optionId, optionId / 10, SELLER_ID, "상품" + optionId, "기본", price, 10,
                true, null, null);
    }

    /** 장바구니에 (옵션 ID, 수량) 순서로 담는다. 항목 ID는 1부터 붙인다 */
    private Cart cartWith(long... optionIdAndQuantity) {
        Cart cart = Cart.create(BUYER_ID);
        long itemId = 1;
        for (int i = 0; i < optionIdAndQuantity.length; i += 2) {
            CartItem item = cart.addItem(optionIdAndQuantity[i], (int) optionIdAndQuantity[i + 1], 99);
            ReflectionTestUtils.setField(item, "id", itemId++);
        }
        return cart;
    }

    /** 저장하면 주문 ID 500, 주문 항목 ID 1부터 붙인다 (IDENTITY 흉내) */
    private void givenSaveAssignsIds() {
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", ORDER_ID);
            long itemId = 1;
            for (OrderItem item : order.getItems()) {
                ReflectionTestUtils.setField(item, "id", itemId++);
            }
            return order;
        });
    }

    /** 판매 가능 옵션 조회 결과 (판매 가능 여부 검사는 OrderSupportTest에서 확인) */
    private void givenOptions(OptionSnapshotDto... options) {
        List<Long> optionIds = Arrays.stream(options).map(OptionSnapshotDto::optionId).toList();
        given(orderSupport.getSaleableOptions(optionIds)).willReturn(Arrays.stream(options)
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity())));
    }

    private void givenSeller() {
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));
    }

    @Test
    @DisplayName("주문을 저장하고, 저장된 주문 항목 ID로 재고를 차감한다")
    void create() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 2, 110L, 1));
        givenOptions(onSale(100L, 15000), onSale(110L, 5000));
        givenSeller();
        givenSaveAssignsIds();

        OrderCreateResponse response = useCase.create(BUYER_ID, null, List.of(1L, 2L), 35000);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.totalPrice()).isEqualTo(35000);
        assertThat(response.orderNumber()).startsWith("ORD-");
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(14));
        verify(productApi).deductStock(List.of(
                new StockChangeDto(ORDER_ID, 1L, 100L, 2),
                new StockChangeDto(ORDER_ID, 2L, 110L, 1)));
    }

    @Test
    @DisplayName("내 장바구니에 없는 항목이면 CART_ITEM_NOT_FOUND")
    void notMyCartItem() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));

        assertThatThrownBy(() -> useCase.create(BUYER_ID, null, List.of(999L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-CART_ITEM_NOT_FOUND");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("본인이 판매하는 상품이면 SELF_PURCHASE_NOT_ALLOWED")
    void selfProduct() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        givenOptions(onSale(100L, 15000));
        // 3번 회원이 2번 판매자
        given(memberApi.findSellerByMemberId(BUYER_ID)).willReturn(Optional.of(
                new SellerDto(SELLER_ID, BUYER_ID, "내상점", SellerStatus.APPROVED)));

        assertThatThrownBy(() -> useCase.create(BUYER_ID, null, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-SELF_PURCHASE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("결제 대기 주문에 들어 있는 옵션이면 CART_ITEM_IN_PENDING_ORDER, 응답에 그 주문 ID를 담는다")
    void inPendingOrder() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        givenOptions(onSale(100L, 15000));
        given(orderRepository.findOrderIdsContainingOptions(BUYER_ID, List.of(100L), Order.PAYMENT_PENDING_STATUSES))
                .willReturn(List.of(ORDER_ID));

        assertThatThrownBy(() -> useCase.create(BUYER_ID, null, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-CART_ITEM_IN_PENDING_ORDER")
                .hasFieldOrPropertyWithValue("data", new PendingOrderConflictResponse(List.of(ORDER_ID)));
    }

    @Test
    @DisplayName("멱등 키를 보내면 주문에 저장한다")
    void createWithIdempotencyKey() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        givenOptions(onSale(100L, 15000));
        givenSeller();
        givenSaveAssignsIds();

        useCase.create(BUYER_ID, "key-1", List.of(1L), 15000);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("같은 멱등 키의 주문이 있으면 그 주문을 돌려주고, 새로 저장하거나 재고를 차감하지 않는다")
    void sameIdempotencyKey() {
        Order existing = Order.create(BUYER_ID, "key-1", List.of(new OrderItemSpec(
                1L, 10L, 100L, SELLER_ID, "상품100", "기본", "테스트상점", 15000, 1)), LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(existing, "id", ORDER_ID);
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        given(orderRepository.findByBuyerIdAndIdempotencyKey(BUYER_ID, "key-1")).willReturn(Optional.of(existing));

        OrderCreateResponse response = useCase.create(BUYER_ID, "key-1", List.of(1L), 15000);

        assertThat(response).isEqualTo(OrderCreateResponse.from(existing));
        verify(orderRepository, never()).save(any());
        verify(productApi, never()).deductStock(any());
    }

    @Test
    @DisplayName("같은 멱등 키의 주문이 취소됐으면 IDEMPOTENCY_KEY_EXPIRED, 새로 저장하거나 재고를 차감하지 않는다")
    void sameIdempotencyKeyCanceled() {
        Order canceled = Order.create(BUYER_ID, "key-1", List.of(new OrderItemSpec(
                1L, 10L, 100L, SELLER_ID, "상품100", "기본", "테스트상점", 15000, 1)), LocalDateTime.now(), 15);
        canceled.cancel(OrderCancelType.EXPIRED, "결제 시간 만료", LocalDateTime.now());
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        given(orderRepository.findByBuyerIdAndIdempotencyKey(BUYER_ID, "key-1")).willReturn(Optional.of(canceled));

        assertThatThrownBy(() -> useCase.create(BUYER_ID, "key-1", List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-IDEMPOTENCY_KEY_EXPIRED");
        verify(orderRepository, never()).save(any());
        verify(productApi, never()).deductStock(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("멱등 키가 비어 있으면 IDEMPOTENCY_KEY_INVALID")
    void blankIdempotencyKey(String key) {
        assertThatThrownBy(() -> useCase.create(BUYER_ID, key, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    @DisplayName("멱등 키가 64자를 넘으면 IDEMPOTENCY_KEY_INVALID, 장바구니를 잠그지 않는다")
    void tooLongIdempotencyKey() {
        assertThatThrownBy(() -> useCase.create(BUYER_ID, "a".repeat(65), List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-IDEMPOTENCY_KEY_INVALID");
        verify(orderSupport, never()).getCartForUpdate(any());
    }

    @Test
    @DisplayName("예상 금액과 현재 금액이 다르면 PRICE_CHANGED, 저장·재고 차감을 하지 않는다")
    void priceChanged() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        givenOptions(onSale(100L, 16000));   // 15000 → 16000
        givenSeller();

        assertThatThrownBy(() -> useCase.create(BUYER_ID, null, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-PRICE_CHANGED");
        verify(orderRepository, never()).save(any());
        verify(productApi, never()).deductStock(any());
    }
}