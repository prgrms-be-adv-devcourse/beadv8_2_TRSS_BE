package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1ProductController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductFacade productFacade;

    @Test
    @DisplayName("상품 검색: 로그인 없이 호출할 수 있고, 정렬을 보내지 않으면 null(검색어에 따라 정함)·품절 제외가 기본값이다")
    void search_defaults() throws Exception {
        given(productFacade.searchProducts(isNull(), isNull(), isNull(), eq(true), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("상품 검색: 없는 정렬 값이면 400 INVALID_REQUEST")
    void search_invalidSort() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("sort", "POPULAR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("상품 검색: 검색어가 100자를 넘으면 400 VALIDATION_FAILED")
    void search_keywordTooLong() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("keyword", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(productFacade, never()).searchProducts(anyString(), anyLong(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("상품 상세: 로그인 없이 호출할 수 있고, 회원 ID 없이(찜 여부 false) 조회한다")
    void detail_public() throws Exception {
        mockMvc.perform(get("/api/v1/products/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).getProductDetail(11L, null);
    }

    @Test
    @DisplayName("상품 상세: 로그인 토큰을 보내면 그 회원 ID로 찜 여부를 함께 조회한다")
    void detail_loggedIn() throws Exception {
        String token = jwtTokenProvider.createAccessToken(3L, MemberRole.USER);

        mockMvc.perform(get("/api/v1/products/11").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(productFacade).getProductDetail(11L, 3L);
    }

    @Test
    @DisplayName("상품 상세: 만료·위조된 토큰이면 로그인하지 않은 것으로 보고 조회한다")
    void detail_invalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/products/11").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk());

        verify(productFacade).getProductDetail(11L, null);
    }
}
