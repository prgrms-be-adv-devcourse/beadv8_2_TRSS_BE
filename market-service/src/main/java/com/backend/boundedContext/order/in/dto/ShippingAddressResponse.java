package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.ShippingAddress;

/**
 * 주문 상세의 배송지
 */
public record ShippingAddressResponse(
        String receiverName,
        String receiverPhone,
        String zipCode,
        String address,
        String addressDetail,
        String requestMessage
) {
    public static ShippingAddressResponse from(ShippingAddress shippingAddress) {
        return new ShippingAddressResponse(
                shippingAddress.getReceiverName(),
                shippingAddress.getReceiverPhone(),
                shippingAddress.getZipCode(),
                shippingAddress.getAddress(),
                shippingAddress.getAddressDetail(),
                shippingAddress.getRequestMessage());
    }
}
