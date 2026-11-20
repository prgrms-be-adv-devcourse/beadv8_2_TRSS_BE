package com.backend.boundedContext.product.in.wishlist;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1WishlistController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1WishlistControllerTest {

    private static final long MEMBER_ID = 3L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductFacade productFacade;

    @Test
    @DisplayName("찜 추가: 로그인한 회원 ID로 찜한다")
    void add() throws Exception {
        mockMvc.perform(put("/api/v1/wishlist/11").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).addWishlist(MEMBER_ID, 11L);
    }

    @Test
    @DisplayName("찜 해제: 로그인한 회원 ID로 해제한다")
    void remove() throws Exception {
        mockMvc.perform(delete("/api/v1/wishlist/11").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).removeWishlist(MEMBER_ID, 11L);
    }

    @Test
    @DisplayName("찜 목록: 로그인한 회원의 목록을 준다")
    void list() throws Exception {
        given(productFacade.getWishlist(eq(MEMBER_ID), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/wishlist").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 UNAUTHORIZED")
    void unauthorized() throws Exception {
        mockMvc.perform(put("/api/v1/wishlist/11"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/wishlist"))
                .andExpect(status().isUnauthorized());

        verify(productFacade, never()).addWishlist(anyLong(), anyLong());
    }

    @Test
    @DisplayName("관리자는 구매자 기능인 찜을 쓸 수 없다 (403 FORBIDDEN)")
    void adminForbidden() throws Exception {
        mockMvc.perform(put("/api/v1/wishlist/11").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        mockMvc.perform(get("/api/v1/wishlist").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isForbidden());

        verify(productFacade, never()).addWishlist(anyLong(), anyLong());
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, role);
    }
}
