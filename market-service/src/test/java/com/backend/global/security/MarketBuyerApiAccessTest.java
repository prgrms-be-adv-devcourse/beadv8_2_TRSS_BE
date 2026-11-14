package com.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.ApiV1CartController;
import com.backend.boundedContext.order.in.ApiV1OrderController;
import com.backend.shared.member.dto.MemberRole;

/**
 * 구매자 API(장바구니·주문) 권한
 * 관리자·물류 담당자는 403, 구매자·판매자는 통과한다. 응답 내용은 각 컨트롤러 테스트에서 확인한다
 */
@WebMvcTest({ApiV1CartController.class, ApiV1OrderController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class MarketBuyerApiAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private OrderFacade orderFacade;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/cart", "/api/v1/orders"})
    @DisplayName("관리자가 구매자 API를 조회하면 403 FORBIDDEN")
    void admin_forbidden(String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token(MemberRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/cart", "/api/v1/orders"})
    @DisplayName("물류 담당자(LOGISTICS)가 구매자 API를 조회하면 403 FORBIDDEN")
    void logistics_forbidden(String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token(MemberRole.LOGISTICS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/cart", "/api/v1/orders"})
    @DisplayName("구매자(USER)는 구매자 API를 호출할 수 있다")
    void user_allowed(String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isOk());
    }

    private String token(MemberRole role) {
        return jwtTokenProvider.createAccessToken(1L, role);
    }
}
