package com.backend.shared.order.event;

import com.backend.shared.event.DomainEvent;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;

import java.time.LocalDateTime;
import java.util.List;

// 결제 완료. 배송지가 있는 주문만 발행한다. 배송 컨텍스트가 받아 배송(READY)을 만든다
public record OrderPaidEvent(Long orderId, String orderNumber, Long buyerId, LocalDateTime paidAt,
                             ShippingAddressDto shippingAddress, List<PaidOrderItemDto> items) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "ORDER";
    }

    @Override
    public Long aggregateId() {
        return orderId;
    }
}
