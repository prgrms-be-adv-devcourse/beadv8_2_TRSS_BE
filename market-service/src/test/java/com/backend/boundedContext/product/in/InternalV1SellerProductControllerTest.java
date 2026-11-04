package com.backend.boundedContext.product.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.shared.product.out.ProductApi;

/**
 * 판매자 상품 내부 API. 보안 설정 전이라 필터 없이 경로·요청 형식만 확인한다
 */
@WebMvcTest(InternalV1SellerProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalV1SellerProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductApi productApi;

    @Test
    @DisplayName("판매 중지 요청을 사유와 함께 ProductApi에 넘긴다")
    void stopAllBySeller() throws Exception {
        mockMvc.perform(post("/internal/v1/sellers/3/products/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"관리자 제재\"}"))
                .andExpect(status().isOk());

        verify(productApi).stopAllBySeller(3L, "관리자 제재");
    }

    @Test
    @DisplayName("사유가 비어 있으면 400이고 ProductApi를 부르지 않는다")
    void stopAllBySeller_blankReason() throws Exception {
        mockMvc.perform(post("/internal/v1/sellers/3/products/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(productApi, never()).stopAllBySeller(any(), any());
    }

    @Test
    @DisplayName("판매 재개 요청을 ProductApi에 넘긴다")
    void resumeAllBySeller() throws Exception {
        mockMvc.perform(post("/internal/v1/sellers/3/products/resume"))
                .andExpect(status().isOk());

        verify(productApi).resumeAllBySeller(3L);
    }
}
