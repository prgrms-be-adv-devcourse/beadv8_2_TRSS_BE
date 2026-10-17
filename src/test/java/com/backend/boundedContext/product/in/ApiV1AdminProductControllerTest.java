package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.AdminProductStopRequestDto;
import com.backend.boundedContext.product.domain.AdminProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ApiV1AdminProductController.class, ApiV1AdminProductStopRequestController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminProductControllerTest {

    private static final AdminProductStopRequestDto REQUEST = new AdminProductStopRequestDto(
            5L, 11L, "수분 크림", 3L, "단종", ProductStopRequestStatus.APPROVED, null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductFacade productFacade;

    @Test
    @DisplayName("상품 목록: 조건을 그대로 넘기고 PageResponse로 돌려준다")
    void getProducts() throws Exception {
        given(productFacade.getProductsForAdmin(eq(3L), eq(AdminProductDisplayStatus.SOLD_OUT), eq(7L), eq("크림"), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        perform(MemberRole.ADMIN, get("/api/v1/admin/products?sellerId=3&status=SOLD_OUT&categoryId=7&keyword=크림"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("상품 목록: ADMIN이 아니면 403 FORBIDDEN")
    void getProducts_notAdmin() throws Exception {
        perform(MemberRole.SELLER, get("/api/v1/admin/products"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("즉시 중지: 사유를 넘긴다")
    void stopProduct() throws Exception {
        perform(MemberRole.ADMIN, post("/api/v1/admin/products/11/stop").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"허위 광고"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).stopProductByAdmin(11L, "허위 광고");
    }

    @Test
    @DisplayName("즉시 중지·반려: 사유가 없거나 공백이거나 300자를 넘으면 400 VALIDATION_FAILED")
    void reason_invalid() throws Exception {
        for (String url : List.of("/api/v1/admin/products/11/stop", "/api/v1/admin/product-stop-requests/5/reject")) {
            for (String body : List.of("{}", "{\"reason\":\"  \"}", "{\"reason\":\"" + "가".repeat(301) + "\"}")) {
                perform(MemberRole.ADMIN, post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
            }
        }
        verify(productFacade, never()).stopProductByAdmin(any(), any());
        verify(productFacade, never()).rejectStopRequest(any(), any());
    }

    @Test
    @DisplayName("요청 목록: status가 없으면 PENDING으로 조회한다")
    void getStopRequests_defaultPending() throws Exception {
        given(productFacade.getStopRequestsForAdmin(eq(ProductStopRequestStatus.PENDING), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        perform(MemberRole.ADMIN, get("/api/v1/admin/product-stop-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());

        verify(productFacade).getStopRequestsForAdmin(eq(ProductStopRequestStatus.PENDING), any(Pageable.class));
        verify(productFacade, never()).getStopRequestsForAdmin(isNull(), any());
    }

    @Test
    @DisplayName("요청 승인: 처리한 요청 정보를 돌려준다")
    void approve() throws Exception {
        given(productFacade.approveStopRequest(5L)).willReturn(REQUEST);

        perform(MemberRole.ADMIN, post("/api/v1/admin/product-stop-requests/5/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(5))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("요청 반려: 반려 사유를 넘긴다")
    void reject() throws Exception {
        given(productFacade.rejectStopRequest(5L, "반려")).willReturn(REQUEST);

        perform(MemberRole.ADMIN, post("/api/v1/admin/product-stop-requests/5/reject").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"반려"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(5));

        verify(productFacade).rejectStopRequest(5L, "반려");
    }

    private ResultActions perform(MemberRole role, MockHttpServletRequestBuilder request) throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, role);
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }
}
