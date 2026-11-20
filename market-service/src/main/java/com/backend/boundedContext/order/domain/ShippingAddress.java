package com.backend.boundedContext.order.domain;

import com.backend.shared.order.dto.ShippingAddressDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 배송지 스냅샷
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingAddress {

    @Column(name = "receiver_name", length = 50)
    private String receiverName;

    // 숫자만 (예: 01012345678)
    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    @Column(name = "address", length = 200)
    private String address;

    @Column(name = "address_detail", length = 200)
    private String addressDetail;

    // 배송 요청사항
    @Column(name = "request_message", length = 100)
    private String requestMessage;

    public static ShippingAddress of(String receiverName, String receiverPhone, String zipCode,
                                     String address, String addressDetail, String requestMessage) {
        ShippingAddress shippingAddress = new ShippingAddress();
        shippingAddress.receiverName = receiverName;
        shippingAddress.receiverPhone = receiverPhone;
        shippingAddress.zipCode = zipCode;
        shippingAddress.address = address;
        shippingAddress.addressDetail = addressDetail;
        shippingAddress.requestMessage = requestMessage;
        return shippingAddress;
    }

    // 결제 완료 이벤트(OrderPaidEvent)에 담는 배송지
    public ShippingAddressDto toDto() {
        return new ShippingAddressDto(receiverName, receiverPhone, zipCode, address, addressDetail, requestMessage);
    }
}
