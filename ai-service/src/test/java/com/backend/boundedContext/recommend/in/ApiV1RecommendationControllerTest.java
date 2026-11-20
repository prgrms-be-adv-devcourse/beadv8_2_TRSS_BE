package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1RecommendationController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1RecommendationControllerTest {

    private static final ProductRecommendationsDto EMPTY = new ProductRecommendationsDto(false, null, List.of());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RecommendFacade recommendFacade;

    @Test
    @DisplayName("로그인 회원은 회원 ID로, size 기본값 10으로 조회한다")
    void member() throws Exception {
        given(recommendFacade.getProducts(3L, 10)).willReturn(EMPTY);

        mockMvc.perform(get("/api/v1/ai/recommendations")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(3L, MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));
        verify(recommendFacade).getProducts(3L, 10);
    }

    @Test
    @DisplayName("비회원·잘못된 토큰도 200이고 회원 ID 없이(null) 조회한다")
    void anonymous() throws Exception {
        given(recommendFacade.getProducts(null, 5)).willReturn(EMPTY);

        mockMvc.perform(get("/api/v1/ai/recommendations").param("size", "5")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ai/recommendations").param("size", "5").header("Authorization", "Bearer 잘못된토큰"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("size는 1~50, 범위 밖이면 400")
    void sizeOutOfRange() throws Exception {
        for (String size : List.of("0", "51")) {
            mockMvc.perform(get("/api/v1/ai/recommendations").param("size", size))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(recommendFacade);
    }

    @Test
    @DisplayName("크리에이터 추천: 로그인 필요(401), 관리자 403, size는 1~20")
    void creatorsSecurityAndSize() throws Exception {
        mockMvc.perform(get("/api/v1/ai/recommendations/creators")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ai/recommendations/creators")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(1L, MemberRole.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ai/recommendations/creators").param("size", "21")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(3L, MemberRole.USER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recommendFacade);
    }

    @Test
    @DisplayName("크리에이터 추천: 로그인 회원 ID와 size 기본값 10으로 조회한다")
    void creators() throws Exception {
        given(recommendFacade.getCreators(3L, 10)).willReturn(new CreatorRecommendationsDto(List.of()));

        mockMvc.perform(get("/api/v1/ai/recommendations/creators")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(3L, MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));
        verify(recommendFacade).getCreators(3L, 10);
    }
}
