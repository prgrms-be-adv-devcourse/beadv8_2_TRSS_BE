package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구매자 쿠폰 API (장바구니 쿠폰 목록, 내 쿠폰 사용 내역)
 */
@WebMvcTest({ApiV1CouponController.class, ApiV1MyCouponUsageController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1CouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CouponFacade couponFacade;

    @Test
    @DisplayName("장바구니 쿠폰 목록: 쉼표로 보낸 cartItemIds를 목록으로 넘긴다")
    void getCartCoupons() throws Exception {
        given(couponFacade.getCartCoupons(7L, List.of(1L, 2L))).willReturn(new CartCouponsDto(62_000L, List.of()));

        mockMvc.perform(get("/api/v1/coupons/cart?cartItemIds=1,2").header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.orderAmount").value(62000))
                .andExpect(jsonPath("$.data.coupons").isArray());
    }

    @Test
    @DisplayName("장바구니 쿠폰 목록: cartItemIds가 없으면 null로 넘긴다")
    void getCartCoupons_withoutCartItems() throws Exception {
        given(couponFacade.getCartCoupons(eq(7L), isNull())).willReturn(new CartCouponsDto(null, List.of()));

        mockMvc.perform(get("/api/v1/coupons/cart").header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isOk());

        verify(couponFacade).getCartCoupons(eq(7L), isNull());
    }

    @Test
    @DisplayName("내 쿠폰 사용 내역: 로그인 회원 ID로 조회한다")
    void getMyCouponUsages() throws Exception {
        given(couponFacade.getMyCouponUsages(eq(7L), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/users/me/coupon-usages").header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/coupons/cart", "/api/v1/users/me/coupon-usages"})
    @DisplayName("관리자가 구매자 쿠폰 API를 호출하면 403 FORBIDDEN")
    void admin_forbidden(String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token(MemberRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/coupons/cart", "/api/v1/users/me/coupon-usages"})
    @DisplayName("로그인하지 않으면 401 UNAUTHORIZED")
    void unauthorized(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    private String token(MemberRole role) {
        return jwtTokenProvider.createAccessToken(7L, role);
    }
}
