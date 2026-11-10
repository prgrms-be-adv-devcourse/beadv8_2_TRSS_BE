package com.backend.boundedContext.review.in;

import com.backend.boundedContext.review.app.ReviewFacade;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1ReviewController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1ReviewControllerTest {

    private static final long MEMBER_ID = 3L;
    private static final String CONTENT = "촉촉하고 향이 좋아요.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ReviewFacade reviewFacade;

    @Test
    @DisplayName("작성: 로그인한 회원 ID로 쓰고 리뷰 ID를 돌려준다")
    void create() throws Exception {
        given(reviewFacade.createReview(MEMBER_ID, 21L, 5, CONTENT)).willReturn(7L);

        mockMvc.perform(post("/api/v1/reviews").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(21L, 5, CONTENT)))
                .andExpect(jsonPath("$.resultCode").value("201-1"))
                .andExpect(jsonPath("$.data.reviewId").value(7));
    }

    @ParameterizedTest(name = "rating {0}")
    @ValueSource(ints = {0, 6})
    @DisplayName("작성: 별점이 1~5가 아니면 400 VALIDATION_FAILED")
    void createInvalidRating(int rating) throws Exception {
        mockMvc.perform(post("/api/v1/reviews").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(21L, rating, CONTENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(reviewFacade, never()).createReview(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("작성: 내용이 10자 미만이거나 1,000자를 넘거나 주문 항목 ID가 없으면 400 VALIDATION_FAILED")
    void createInvalidContent() throws Exception {
        for (String body : List.of(
                createBody(21L, 5, "짧아요"),
                createBody(21L, 5, "가".repeat(1_001)),
                "{\"rating\":5,\"content\":\"" + CONTENT + "\"}")) {
            mockMvc.perform(post("/api/v1/reviews").header("Authorization", bearer(MemberRole.USER))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }

        verify(reviewFacade, never()).createReview(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("수정: 보낸 값만 넘기고, 아무것도 보내지 않으면 400 VALIDATION_FAILED")
    void modify() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/7").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));
        verify(reviewFacade).modifyReview(eq(MEMBER_ID), eq(7L), eq(4), isNull());

        mockMvc.perform(patch("/api/v1/reviews/7").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/reviews/7").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + " ".repeat(12) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("삭제: 로그인한 회원 ID로 지운다")
    void deleteReview() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/7").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(reviewFacade).deleteReview(MEMBER_ID, 7L);
    }

    @Test
    @DisplayName("내 리뷰 목록: 로그인한 회원의 목록을 준다")
    void myReviews() throws Exception {
        given(reviewFacade.getMyReviews(eq(MEMBER_ID), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/reviews/me").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 UNAUTHORIZED")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(21L, 5, CONTENT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/reviews/me"))
                .andExpect(status().isUnauthorized());

        verify(reviewFacade, never()).createReview(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("관리자는 구매자 기능인 리뷰를 쓸 수 없다 (403 FORBIDDEN)")
    void adminForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(21L, 5, CONTENT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/reviews/7").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isForbidden());

        verify(reviewFacade, never()).deleteReview(anyLong(), anyLong());
    }

    private static String createBody(long orderItemId, int rating, String content) {
        return "{\"orderItemId\":" + orderItemId + ",\"rating\":" + rating + ",\"content\":\"" + content + "\"}";
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, role);
    }
}
