package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.RecommendErrorCode;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.global.exception.DomainException;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1SurveyController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1SurveyControllerTest {

    private static final String VALID_BODY = """
            {"skinType":"OILY","concerns":["TROUBLE","PORE"],"preferredIngredients":["NIACINAMIDE","CICA"],
             "avoidedIngredients":["FRAGRANCE","ALCOHOL"],"interestCategories":["SKINCARE","SUNCARE"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RecommendFacade recommendFacade;

    @Test
    @DisplayName("설문 저장: 로그인 회원 기준으로 코드를 그대로 넘기고 저장한 프로필을 돌려줌")
    void save() throws Exception {
        given(recommendFacade.saveSurvey(any(), any(), any(), any(), any(), any())).willReturn(new BeautyProfileDto(
                SkinType.OILY, Set.of(SkinConcern.TROUBLE), Set.of(NIACINAMIDE), Set.of(FRAGRANCE), Set.of(BeautyCategory.SKINCARE),
                LocalDateTime.of(2026, 11, 14, 12, 0)));

        mockMvc.perform(put("/api/v1/ai/survey/me").header("Authorization", bearer(3L, MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.skinType").value("OILY"));

        verify(recommendFacade).saveSurvey(3L, SkinType.OILY, List.of(SkinConcern.TROUBLE, SkinConcern.PORE),
                List.of(NIACINAMIDE, CICA), List.of(FRAGRANCE, ALCOHOL), List.of(BeautyCategory.SKINCARE, BeautyCategory.SUNCARE));
    }

    @Test
    @DisplayName("설문 저장: 고민 4개는 400-VALIDATION_FAILED")
    void tooManyConcerns() throws Exception {
        String body = VALID_BODY.replace("[\"TROUBLE\",\"PORE\"]", "[\"TROUBLE\",\"PORE\",\"SEBUM\",\"DRYNESS\"]");

        mockMvc.perform(put("/api/v1/ai/survey/me").header("Authorization", bearer(3L, MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        verifyNoInteractions(recommendFacade);
    }

    @Test
    @DisplayName("설문 저장: 모르는 코드는 400-INVALID_REQUEST")
    void unknownCode() throws Exception {
        String body = VALID_BODY.replace("\"OILY\"", "\"OILY_SKIN\"");

        mockMvc.perform(put("/api/v1/ai/survey/me").header("Authorization", bearer(3L, MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("설문 조회: 설문을 안 했으면 404-SURVEY_NOT_FOUND")
    void getNotFound() throws Exception {
        given(recommendFacade.getSurvey(3L)).willThrow(new DomainException(RecommendErrorCode.SURVEY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai/survey/me").header("Authorization", bearer(3L, MemberRole.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-SURVEY_NOT_FOUND"));
    }

    @Test
    @DisplayName("설문: 비로그인 401, 관리자 403")
    void security() throws Exception {
        mockMvc.perform(get("/api/v1/ai/survey/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ai/survey/me").header("Authorization", bearer(1L, MemberRole.ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recommendFacade);
    }

    private String bearer(Long memberId, MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(memberId, role);
    }
}
