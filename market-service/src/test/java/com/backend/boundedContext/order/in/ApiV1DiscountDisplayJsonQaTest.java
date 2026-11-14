package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.CartItemResponse;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.in.dto.RefundStatus;
import com.backend.boundedContext.order.in.dto.SellerOrderItemResponse;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [QA] TRSS-222 응답 JSON 계약: 판매자 주문 항목 discountAmount·discountedLineAmount,
 * 장바구니·주문 상세 항목 listPrice·discountRate 추가와 기존 필드 유지, null 할인율이 키째 빠지지 않는지, 권한.
 */
@WebMvcTest({ApiV1CartController.class, ApiV1OrderController.class, ApiV1SellerOrderItemController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1DiscountDisplayJsonQaTest {

    private static final long MEMBER_ID = 3L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private OrderFacade orderFacade;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, role);
    }

    @Test
    @DisplayName("GET /api/v1/seller/order-items: discountAmount·discountedLineAmount가 추가되고 기존 금액·상태 필드는 그대로다")
    void sellerOrderItems_jsonFields() throws Exception {
        SellerOrderItemResponse row = new SellerOrderItemResponse(900L, 500L, "20261112-0001", LocalDateTime.now(),
                10L, "약산성 토너", "300ml", 3, 12_000, 36_000, 5_000, 31_000, OrderStatus.PAID);
        given(orderFacade.getSellerOrderItems(eq(MEMBER_ID), isNull(), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageResponse<>(List.of(row), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/seller/order-items").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(900))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(12_000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(36_000))
                .andExpect(jsonPath("$.data.items[0].discountAmount").value(5_000))
                .andExpect(jsonPath("$.data.items[0].discountedLineAmount").value(31_000))
                .andExpect(jsonPath("$.data.items[0].status").value("PAID"));
    }

    @Test
    @DisplayName("GET /api/v1/seller/order-items: 토큰 없으면 401, ADMIN·LOGISTICS는 403")
    void sellerOrderItems_auth() throws Exception {
        mockMvc.perform(get("/api/v1/seller/order-items"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/seller/order-items").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/seller/order-items").header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/cart: 항목에 listPrice·discountRate가 추가되고, 할인 없는 항목의 discountRate는 키가 있고 값이 null이다")
    void cart_itemJsonFields() throws Exception {
        CartItemResponse discounted = new CartItemResponse(1L, 100L, 10L, "상점", "블러셔", "대용량", null,
                13_000, 15_000, 20, 2, 26_000, true, true, null);
        CartItemResponse plain = new CartItemResponse(2L, 200L, 20L, "상점", "립스틱", "기본", null,
                9_000, 9_000, null, 1, 9_000, true, true, null);
        given(orderFacade.getCart(MEMBER_ID)).willReturn(
                new CartResponse(List.of(discounted, plain), 2, 35_000, true, 0, 35_000, 0));

        mockMvc.perform(get("/api/v1/cart").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].price").value(13_000))
                .andExpect(jsonPath("$.data.items[0].listPrice").value(15_000))
                .andExpect(jsonPath("$.data.items[0].discountRate").value(20))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(26_000))
                .andExpect(jsonPath("$.data.items[1].listPrice").value(9_000))
                // 키가 빠지지 않고 null로 나간다 (jsonPath().exists()는 null 값을 없음으로 보므로 본문으로 확인)
                .andExpect(jsonPath("$.data.items[1].discountRate").value(nullValue()))
                .andExpect(content().string(containsString("\"discountRate\":null")))
                .andExpect(jsonPath("$.data.expectedTotalPrice").value(35_000));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id}: 항목에 listPrice·discountRate가 추가되고 unitPrice·lineAmount·discountAmount는 그대로다")
    void orderDetail_itemJsonFields() throws Exception {
        OrderItemResponse item = new OrderItemResponse(900L, 10L, 100L, "블러셔", "대용량", "상점",
                13_000, 2, 26_000, 0, 15_000, 20, "https://img.example/blusher.jpg");
        OrderItemResponse old = new OrderItemResponse(901L, 11L, 101L, "립스틱", "기본", "상점",
                5_000, 1, 5_000, 0, 5_000, null, null);
        OrderDetailResponse detail = new OrderDetailResponse(500L, "20261112-0001", OrderStatus.PAID, 31_000,
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(15), 0, LocalDateTime.now(), null, null,
                List.of(item, old), List.of(), null, null, null, false, 0, 0, 31_000, 0, false,
                null, null, null, null, null, null, RefundStatus.NONE);
        given(orderFacade.getOrderDetail(MEMBER_ID, 500L)).willReturn(detail);

        mockMvc.perform(get("/api/v1/orders/500").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(13_000))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(26_000))
                .andExpect(jsonPath("$.data.items[0].discountAmount").value(0))
                .andExpect(jsonPath("$.data.items[0].listPrice").value(15_000))
                .andExpect(jsonPath("$.data.items[0].discountRate").value(20))
                .andExpect(jsonPath("$.data.items[1].listPrice").value(5_000))
                // 키가 빠지지 않고 null로 나간다 (jsonPath().exists()는 null 값을 없음으로 보므로 본문으로 확인)
                .andExpect(jsonPath("$.data.items[1].discountRate").value(nullValue()))
                .andExpect(content().string(containsString("\"discountRate\":null")))
                .andExpect(jsonPath("$.data.payAmount").value(31_000));
    }
}
