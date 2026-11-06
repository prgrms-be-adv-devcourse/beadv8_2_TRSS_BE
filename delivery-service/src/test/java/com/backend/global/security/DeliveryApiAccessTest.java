package com.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.shared.member.dto.MemberRole;

/**
 * delivery-service 경로 권한 (common SecurityConfig)
 * - /api/v1/logistics/** : LOGISTICS만. 관리자·구매자는 403, 익명은 401
 * - POST /webhooks/delivery-tracker/** : 인증 없음 (GET은 인증 필요)
 * - LOGISTICS는 구매·판매·운영 경로에서 403
 * 실제 컨트롤러가 아직 없어 테스트 전용 컨트롤러로 확인한다
 */
@WebMvcTest(DeliveryApiAccessTest.StubController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class,
        DeliveryApiAccessTest.StubController.class})
class DeliveryApiAccessTest {

    private static final String LOGISTICS_URL = "/api/v1/logistics/ping";
    private static final String WEBHOOK_URL = "/webhooks/delivery-tracker/some-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @RestController
    static class StubController {

        @GetMapping(LOGISTICS_URL)
        String logistics() {
            return "ok";
        }

        @PostMapping(WEBHOOK_URL)
        String webhook() {
            return "ok";
        }

        @GetMapping(WEBHOOK_URL)
        String webhookGet() {
            return "ok";
        }
    }

    @Test
    @DisplayName("물류 담당자(LOGISTICS)는 물류 API를 호출할 수 있다")
    void logistics_allowed() throws Exception {
        mockMvc.perform(get(LOGISTICS_URL).header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("물류 담당자가 없는 물류 경로를 부르면 권한은 통과하고 404")
    void logistics_unknownPath_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/logistics/unknown").header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"USER", "ADMIN"})
    @DisplayName("관리자·구매자가 물류 API를 부르면 403 FORBIDDEN")
    void nonLogistics_forbidden(MemberRole role) throws Exception {
        mockMvc.perform(get(LOGISTICS_URL).header("Authorization", bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("로그인하지 않고 물류 API를 부르면 401")
    void anonymous_logistics_unauthorized() throws Exception {
        mockMvc.perform(get(LOGISTICS_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Delivery Tracker 콜백(POST)은 인증 없이 통과한다")
    void webhookPost_anonymous_allowed() throws Exception {
        mockMvc.perform(post(WEBHOOK_URL))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("콜백 경로라도 POST가 아니면 인증이 필요하다 (GET 익명 401)")
    void webhookGet_anonymous_unauthorized() throws Exception {
        mockMvc.perform(get(WEBHOOK_URL))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/orders", "/api/v1/cart", "/api/v1/wishlist", "/api/v1/wallet", "/api/v1/topups",
            "/api/v1/sellers/me", "/api/v1/seller/products", "/api/v1/admin/users", "/api/v1/users/me/withdrawal"})
    @DisplayName("물류 담당자는 구매·판매·운영 API에서 403 FORBIDDEN")
    void logistics_forbiddenOnOtherRoleApis(String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("물류 담당자는 회원 탈퇴(DELETE /api/v1/users/me)를 할 수 없다")
    void logistics_withdraw_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isForbidden());
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }
}
