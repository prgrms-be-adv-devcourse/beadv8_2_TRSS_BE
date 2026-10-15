package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        useCase = new OrderCreateOrderUseCase(orderSupport, orderRepository, new OrderPolicy(15, 7, 99, 100), productApi, memberApi);
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

        OrderCreateResponse response = useCase.create(BUYER_ID, List.of(1L, 2L), 35000);

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

        assertThatThrownBy(() -> useCase.create(BUYER_ID, List.of(999L), 15000))
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

        assertThatThrownBy(() -> useCase.create(BUYER_ID, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-SELF_PURCHASE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("결제 대기 주문에 들어 있는 옵션이면 CART_ITEM_IN_PENDING_ORDER")
    void inPendingOrder() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        givenOptions(onSale(100L, 15000));
        given(orderSupport.findOptionIdsInPendingOrders(BUYER_ID, List.of(100L))).willReturn(Set.of(100L));

        assertThatThrownBy(() -> useCase.create(BUYER_ID, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-CART_ITEM_IN_PENDING_ORDER");
    }

    @Test
    @DisplayName("예상 금액과 현재 금액이 다르면 PRICE_CHANGED, 저장·재고 차감을 하지 않는다")
    void priceChanged() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cartWith(100L, 1));
        givenOptions(onSale(100L, 16000));   // 15000 → 16000
        givenSeller();

        assertThatThrownBy(() -> useCase.create(BUYER_ID, List.of(1L), 15000))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-PRICE_CHANGED");
        verify(orderRepository, never()).save(any());
        verify(productApi, never()).deductStock(any());
    }
}