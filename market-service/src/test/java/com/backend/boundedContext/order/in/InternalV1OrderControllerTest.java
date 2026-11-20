package com.backend.boundedContext.order.in;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.shared.order.out.OrderApi;

/**
 * 주문 내부 API. 보안 설정 전이라 필터 없이 경로·파라미터·응답 형식만 확인한다
 */
@WebMvcTest(InternalV1OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalV1OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderApi orderApi;

    @Test
    @DisplayName("구매자 진행 중 주문 여부를 data에 담아 돌려준다")
    void hasActiveOrders() throws Exception {
        given(orderApi.hasActiveOrders(1L)).willReturn(true);

        mockMvc.perform(get("/internal/v1/orders/active/exists").param("buyerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("판매자 진행 중 판매 여부를 data에 담아 돌려준다")
    void hasActiveSales() throws Exception {
        given(orderApi.hasActiveSales(2L)).willReturn(false);

        mockMvc.perform(get("/internal/v1/orders/active-sales/exists").param("sellerId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    @DisplayName("최근 주문이 없으면 빈 목록을 돌려준다")
    void getRecentOrders() throws Exception {
        given(orderApi.getRecentOrders(1L, 5)).willReturn(List.of());

        mockMvc.perform(get("/internal/v1/orders/recent").param("buyerId", "1").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
