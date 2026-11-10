package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.ShippingAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 주문 배송지
 *
 * @param receiverName   수령인 (50자 이하)
 * @param receiverPhone  수령인 연락처. 숫자만, 0으로 시작하는 9~11자리 (예: 01012345678)
 * @param zipCode        우편번호 5자리
 * @param address        기본 주소 (200자 이하)
 * @param addressDetail  상세 주소 (선택, 200자 이하)
 * @param requestMessage 배송 요청사항 (선택, 100자 이하)
 */
public record ShippingAddressRequest(
        @NotBlank @Size(max = 50) String receiverName,
        @NotBlank @Pattern(regexp = "^0\\d{8,10}$", message = "연락처는 0으로 시작하는 숫자 9~11자리로 입력해 주세요.") String receiverPhone,
        @NotBlank @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리로 입력해 주세요.") String zipCode,
        @NotBlank @Size(max = 200) String address,
        @Size(max = 200) String addressDetail,
        @Size(max = 100) String requestMessage
) {
    public ShippingAddress toShippingAddress() {
        return ShippingAddress.of(receiverName, receiverPhone, zipCode, address, addressDetail, requestMessage);
    }
}
