package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [QA] TRSS-210 응답 JSON 계약: 장바구니 배송비 3필드, 주문 목록 payAmount 추가와 기존 필드 유지, 비로그인 401.
 */
@WebMvcTest({ApiV1CartController.class, ApiV1OrderController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1ShippingFeeJsonQaTest {

    private static final long MEMBER_ID = 3L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private OrderFacade orderFacade;

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, MemberRole.USER);
    }

    @Test
    @DisplayName("GET /api/v1/cart: 기존 필드(expectedTotalPrice·orderable)와 새 필드 3개가 함께 나간다")
    void cart_jsonFields() throws Exception {
        given(orderFacade.getCart(MEMBER_ID)).willReturn(new CartResponse(List.of(), 1, 25_000, true, 2_500, 27_500, 5_000));

        mockMvc.perform(get("/api/v1/cart").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCount").value(1))
                .andExpect(jsonPath("$.data.expectedTotalPrice").value(25_000))
                .andExpect(jsonPath("$.data.orderable").value(true))
                .andExpect(jsonPath("$.data.shippingFee").value(2_500))
                .andExpect(jsonPath("$.data.expectedPayAmount").value(27_500))
                .andExpect(jsonPath("$.data.remainingForFreeShipping").value(5_000));
    }

    @Test
    @DisplayName("GET /api/v1/cart: 토큰 없으면 401")
    void cart_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/orders: payAmount가 추가되고 기존 totalPrice도 그대로 나간다")
    void orders_jsonFields() throws Exception {
        OrderSummaryResponse summary = new OrderSummaryResponse(9L, "20261111-0001", OrderStatus.CREATED, 22_500,
                "립스틱", null, 1, LocalDateTime.now(), LocalDateTime.now().plusMinutes(15), 900, null, null, 22_500);
        given(orderFacade.getOrders(eq(MEMBER_ID), any(), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(summary), 0, 10, 1, 1));

        mockMvc.perform(get("/api/v1/orders").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].totalPrice").value(22_500))
                .andExpect(jsonPath("$.data.items[0].payAmount").value(22_500));
    }
}
