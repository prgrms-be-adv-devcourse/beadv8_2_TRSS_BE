package com.backend.shared.order.dto;

// 주문 배송지 스냅샷
public record ShippingAddressDto(String receiverName, String receiverPhone, String zipCode,
                                 String address, String addressDetail, String requestMessage) { }
