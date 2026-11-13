package com.backend.boundedContext.order.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.PriceChangedResponse;
import com.backend.boundedContext.order.in.dto.ShippingAddressRequest;
import com.backend.boundedContext.order.in.dto.ShippingAddressResponse;
import com.backend.global.exception.DomainException;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.order.dto.OrderStatus;

/**
 * [QA] PR 4 주문 API의 HTTP 계약: 배송지 검증 400, 배송지 생략 허용, 주문 상세의 배송 필드 JSON, 409 ORDER_NOT_DELIVERED.
 */
@WebMvcTest(ApiV1OrderController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1OrderControllerDeliveryTest {

    private static final long MEMBER_ID = 3L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private OrderFacade orderFacade;

    @Test
    @DisplayName("주문 생성: 예상 결제 금액이 다르면 409-PRICE_CHANGED이고 data.currentAmount에 실제 결제 금액을 담는다")
    void create_priceChanged() throws Exception {
        given(orderFacade.createOrder(eq(MEMBER_ID), isNull(), eq(List.of(1L)), eq(22000L), isNull(), any()))
                .willThrow(new DomainException(OrderErrorCode.PRICE_CHANGED, new PriceChangedResponse(25000)));

        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[1],\"expectedTotalPrice\":22000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-PRICE_CHANGED"))
                .andExpect(jsonPath("$.data.currentAmount").value(25000));
    }

    @Test
    @DisplayName("주문 생성: shippingAddress를 생략하면(지금 프론트) 201이고 Facade에 null로 넘긴다")
    void create_withoutShippingAddress() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[1,2],\"expectedTotalPrice\":30000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultCode").value("201-1"));

        verify(orderFacade).createOrder(eq(MEMBER_ID), isNull(), eq(List.of(1L, 2L)), eq(30000L), isNull(), any());
    }

    @Test
    @DisplayName("주문 생성: 올바른 shippingAddress는 그대로 Facade에 넘긴다")
    void create_withShippingAddress() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("01012345678", "06236")))
                .andExpect(status().isCreated());

        ArgumentCaptor<ShippingAddressRequest> captor = ArgumentCaptor.forClass(ShippingAddressRequest.class);
        verify(orderFacade).createOrder(eq(MEMBER_ID), isNull(), eq(List.of(1L)), eq(30000L), captor.capture(), any());
        assertThat(captor.getValue().receiverName()).isEqualTo("홍길동");
        assertThat(captor.getValue().zipCode()).isEqualTo("06236");
    }

    @Test
    @DisplayName("주문 생성: 전화번호 형식(하이픈 포함)이 틀리면 400 VALIDATION_FAILED, 주문을 만들지 않는다")
    void create_invalidPhone_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("010-1234-5678", "06236")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("shippingAddress.receiverPhone"));

        verify(orderFacade, never()).createOrder(anyLong(), any(), anyList(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("주문 생성: 우편번호가 5자리 숫자가 아니면 400, 주문을 만들지 않는다")
    void create_invalidZipCode_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("01012345678", "1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("shippingAddress.zipCode"));

        verify(orderFacade, never()).createOrder(anyLong(), any(), anyList(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("주문 생성: 400 응답 본문에 입력한 배송지 값(전화번호)을 되돌려 주지 않는다")
    void create_invalid_doesNotEchoValue() throws Exception {
        String response = mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("010-9999-8888", "06236")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("010-9999-8888");
    }

    @Test
    @DisplayName("주문 상세: delivery 장애 응답(deliveryUnavailable = true)도 200이고 배송 필드가 JSON에 나간다")
    void detail_deliveryUnavailable_200() throws Exception {
        given(orderFacade.getOrderDetail(MEMBER_ID, 9L)).willReturn(new OrderDetailResponse(
                9L, "20261108-0001", OrderStatus.PAID, 10_000, LocalDateTime.now(), LocalDateTime.now(), 0,
                LocalDateTime.now(), null, null, List.of(), List.of(),
                new ShippingAddressResponse("홍길동", "01012345678", "06236", "서울", null, null),
                null, null, true, 0, 0, 12_500, 2_500, false));

        mockMvc.perform(get("/api/v1/orders/9").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryUnavailable").value(true))
                .andExpect(jsonPath("$.data.delivery").doesNotExist())
                .andExpect(jsonPath("$.data.shippingAddress.zipCode").value("06236"))
                .andExpect(jsonPath("$.data.shippingFee").value(2_500));
    }

    @Test
    @DisplayName("구매 확정: 배송 완료 전이면 409 ORDER_NOT_DELIVERED")
    void confirm_notDelivered_409() throws Exception {
        given(orderFacade.confirmOrder(MEMBER_ID, 9L)).willThrow(new DomainException(OrderErrorCode.ORDER_NOT_DELIVERED));

        mockMvc.perform(post("/api/v1/orders/9/confirm").header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-ORDER_NOT_DELIVERED"));
    }

    private static String body(String phone, String zipCode) {
        return """
                {"cartItemIds":[1],"expectedTotalPrice":30000,
                 "shippingAddress":{"receiverName":"홍길동","receiverPhone":"%s","zipCode":"%s",
                                    "address":"서울 강남구 테헤란로 1","addressDetail":"101호","requestMessage":"문 앞"}}
                """.formatted(phone, zipCode);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, MemberRole.USER);
    }
}
