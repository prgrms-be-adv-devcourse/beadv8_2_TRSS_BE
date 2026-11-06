package com.backend.shared.order.dto;

// 주문 배송지 스냅샷 (주문할 때 받은 값 그대로). 로그에 남기지 않는다
public record ShippingAddressDto(String receiverName, String receiverPhone, String zipCode,
                                 String address, String addressDetail, String requestMessage) { }
