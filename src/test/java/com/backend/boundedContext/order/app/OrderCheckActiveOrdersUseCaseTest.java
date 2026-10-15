package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderCheckActiveOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;
    @InjectMocks
    OrderCheckActiveOrdersUseCase useCase;

    // 진행 중: 결제 대기, 결제 완료, 반품 요청 / 종료: 구매확정, 취소, 반품완료
    private static boolean isActiveStatuses(Collection<OrderStatus> statuses) {
        return statuses.size() == 4
                && statuses.containsAll(List.of(
                        OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED, OrderStatus.PAID, OrderStatus.RETURN_REQUESTED));
    }

    @Test
    @DisplayName("hasActiveOrders: 구매자 기준으로 진행 중 상태(CREATED, PAYMENT_FAILED, PAID, RETURN_REQUESTED) 주문 존재 여부를 돌려준다")
    void hasActiveOrders() {
        given(orderRepository.existsByBuyerIdAndStatusIn(eq(3L), argThat(OrderCheckActiveOrdersUseCaseTest::isActiveStatuses)))
                .willReturn(true);

        assertThat(useCase.hasActiveOrders(3L)).isTrue();
    }

    @Test
    @DisplayName("hasActiveSales: 판매자 기준으로 진행 중 상태 주문 존재 여부를 돌려준다")
    void hasActiveSales() {
        given(orderRepository.existsSalesBySellerIdAndStatusIn(eq(7L), argThat(OrderCheckActiveOrdersUseCaseTest::isActiveStatuses)))
                .willReturn(false);

        assertThat(useCase.hasActiveSales(7L)).isFalse();
    }
}
