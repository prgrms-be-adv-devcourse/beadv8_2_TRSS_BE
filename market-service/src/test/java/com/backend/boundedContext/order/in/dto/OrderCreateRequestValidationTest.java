package com.backend.boundedContext.order.in.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * 주문 생성 요청의 배송지 검증. 컨트롤러는 @Valid로 같은 검증을 하고, 실패하면 400(VALIDATION) 응답이다.
 */
class OrderCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static ShippingAddressRequest address(String phone, String zipCode) {
        return new ShippingAddressRequest("홍길동", phone, zipCode, "서울 강남구 테헤란로 1", "101호", "문 앞");
    }

    private static Set<String> invalidFields(ShippingAddressRequest shippingAddress) {
        Set<ConstraintViolation<OrderCreateRequest>> violations =
                validator.validate(new OrderCreateRequest(List.of(1L), 15000L, shippingAddress));
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("배송지 없이 보내면 통과한다 (프론트가 붙기 전까지 선택 값)")
    void withoutShippingAddress() {
        assertThat(invalidFields(null)).isEmpty();
    }

    @Test
    @DisplayName("형식에 맞는 배송지는 통과한다 (상세 주소·요청사항은 생략 가능)")
    void validShippingAddress() {
        assertThat(invalidFields(address("01012345678", "06236"))).isEmpty();
        assertThat(invalidFields(address("021234567", "06236"))).isEmpty();   // 지역번호 9자리
        assertThat(invalidFields(new ShippingAddressRequest("홍길동", "01012345678", "06236", "서울", null, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "1012345678", "0101234", "010123456789", "0101234567a", ""})
    @DisplayName("연락처가 0으로 시작하는 숫자 9~11자리가 아니면 실패한다")
    void invalidPhone(String phone) {
        assertThat(invalidFields(address(phone, "06236"))).containsExactly("shippingAddress.receiverPhone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0623", "062366", "06-236", "abcde", ""})
    @DisplayName("우편번호가 숫자 5자리가 아니면 실패한다")
    void invalidZipCode(String zipCode) {
        assertThat(invalidFields(address("01012345678", zipCode))).containsExactly("shippingAddress.zipCode");
    }

    @Test
    @DisplayName("수령인·주소가 비어 있거나 길이를 넘으면 실패한다")
    void blankOrTooLong() {
        assertThat(invalidFields(new ShippingAddressRequest(" ", "01012345678", "06236", " ", null, null)))
                .containsExactlyInAnyOrder("shippingAddress.receiverName", "shippingAddress.address");
        assertThat(invalidFields(new ShippingAddressRequest("가".repeat(51), "01012345678", "06236", "서울",
                "가".repeat(201), "가".repeat(101))))
                .containsExactlyInAnyOrder("shippingAddress.receiverName", "shippingAddress.addressDetail",
                        "shippingAddress.requestMessage");
    }
}
