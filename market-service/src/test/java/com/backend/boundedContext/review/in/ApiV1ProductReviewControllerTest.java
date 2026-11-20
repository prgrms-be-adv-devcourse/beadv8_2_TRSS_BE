package com.backend.boundedContext.review.in;

import com.backend.boundedContext.review.app.ReviewFacade;
import com.backend.boundedContext.review.app.dto.ReviewSummaryDto;
import com.backend.boundedContext.review.domain.ReviewSort;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1ProductReviewController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1ProductReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewFacade reviewFacade;

    @Test
    @DisplayName("상품 리뷰 목록: 로그인 없이 호출할 수 있고, 정렬 기본값은 최신순이다")
    void listDefaults() throws Exception {
        given(reviewFacade.getProductReviews(eq(11L), eq(ReviewSort.LATEST), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/products/11/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("상품 리뷰 목록: 별점순 정렬을 넘기고, 없는 정렬 값이면 400 INVALID_REQUEST")
    void listSort() throws Exception {
        given(reviewFacade.getProductReviews(eq(11L), eq(ReviewSort.RATING_HIGH), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/products/11/reviews").param("sort", "RATING_HIGH"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/11/reviews").param("sort", "HELPFUL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("평점 요약: 로그인 없이 호출할 수 있고, 별점별 개수는 5점부터 나온다")
    void summary() throws Exception {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        counts.put(5, 1L);
        counts.put(4, 2L);
        counts.put(3, 0L);
        counts.put(2, 0L);
        counts.put(1, 0L);
        given(reviewFacade.getReviewSummary(11L)).willReturn(new ReviewSummaryDto(4.3, 3, counts));

        mockMvc.perform(get("/api/v1/products/11/reviews/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageRating").value(4.3))
                .andExpect(jsonPath("$.data.reviewCount").value(3))
                .andExpect(jsonPath("$.data.ratingCounts.5").value(1))
                .andExpect(jsonPath("$.data.ratingCounts.4").value(2));
    }
}
