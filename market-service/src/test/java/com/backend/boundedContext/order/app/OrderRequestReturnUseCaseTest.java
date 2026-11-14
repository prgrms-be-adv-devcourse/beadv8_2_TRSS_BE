package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.ReturnRequestResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrderRequestReturnUseCaseTest {

    @Mock MemberApi memberApi;
    @Mock OrderRepository orderRepository;
    @InjectMocks OrderRequestReturnUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;
    private static final LocalDateTime PAID_AT = LocalDateTime.now().minusDays(3);

    private Order deliveredOrder() {
        Order order = Order.create(BUYER_ID, null,
                List.of(new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                ShippingAddress.of("김반품수령", "01011112222", "06236", "서울 강남구 반품로 1", "202호", "문 앞"),
                PAID_AT.minusMinutes(1), 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.pay(PAID_AT);
        order.markDelivered(PAID_AT.plusDays(1));
        return order;
    }

    @Test
    @DisplayName("활성 회원 확인 → 내 주문을 잠가 조회 → RETURN_REQUESTED로 바꾸고 요청 시각을 돌려준다")
    void request() {
        Order order = deliveredOrder();
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        ReturnRequestResponse response = useCase.request(BUYER_ID, ORDER_ID, ReturnReasonCode.DEFECTIVE, "깨져서 왔어요");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(response.returnRequestedAt()).isNotNull().isEqualTo(order.getReturnRequestedAt());
        InOrder inOrder = inOrder(memberApi, orderRepository);
        inOrder.verify(memberApi).checkActiveMember(BUYER_ID);
        inOrder.verify(orderRepository).findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID);
    }

    @Test
    @DisplayName("없는 주문·다른 회원의 주문이면 404 ORDER_NOT_FOUND")
    void notFound() {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.request(BUYER_ID, ORDER_ID, ReturnReasonCode.DEFECTIVE, null))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", OrderErrorCode.ORDER_NOT_FOUND.resultCode());
    }

    @Test
    @DisplayName("활성 회원이 아니면 member 오류를 그대로 던지고 주문을 조회하지 않는다")
    void inactiveMember() {
        willThrow(new DomainException(GlobalErrorCode.FORBIDDEN)).given(memberApi).checkActiveMember(BUYER_ID);

        assertThatThrownBy(() -> useCase.request(BUYER_ID, ORDER_ID, ReturnReasonCode.DEFECTIVE, null))
                .isInstanceOf(DomainException.class);
        verify(orderRepository, never()).findByIdAndBuyerIdForUpdate(anyLong(), any());
    }

    @Test
    @DisplayName("로그에는 주문 id와 사유 코드만 남고 상세 사유·배송지·연락처는 남지 않는다")
    void log_noPersonalData(CapturedOutput output) {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(deliveredOrder()));

        useCase.request(BUYER_ID, ORDER_ID, ReturnReasonCode.OTHER, "개인 사정 상세 사유");

        assertThat(output).contains("반품 요청: orderId=" + ORDER_ID + ", reasonCode=OTHER");
        assertThat(output.getAll()).doesNotContain("개인 사정 상세 사유", "김반품수령", "01011112222", "서울 강남구 반품로 1", "202호");
    }
}
