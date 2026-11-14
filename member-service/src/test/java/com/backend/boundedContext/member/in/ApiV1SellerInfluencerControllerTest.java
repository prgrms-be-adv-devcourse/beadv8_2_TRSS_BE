package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1SellerInfluencerController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1SellerInfluencerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberInfluencerFacade memberInfluencerFacade;

    @Test
    @DisplayName("검색: 토큰의 회원 ID와 키워드로 검색해 돌려준다")
    void search_success() throws Exception {
        given(memberInfluencerFacade.searchInfluencers(eq(2L), eq("민지"), any())).willReturn(
                new PageResponse<>(List.of(new InfluencerProfileResponse(7L, "민지", "소개")), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/seller/influencers").param("keyword", "민지")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(2L, MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].influencerId").value(7));
    }

    @Test
    @DisplayName("검색: 토큰이 없으면 401, 관리자면 403")
    void search_noTokenOrAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/seller/influencers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/seller/influencers")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(2L, MemberRole.ADMIN)))
                .andExpect(status().isForbidden());
    }
}
