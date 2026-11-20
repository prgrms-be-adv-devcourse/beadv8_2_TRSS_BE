package com.backend.boundedContext.faq.in;

import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1FaqController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1FaqControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private FaqFacade faqFacade;

    @Test
    @DisplayName("로그인하면 앞뒤 공백을 지운 질문으로 검색함")
    void search() throws Exception {
        given(faqFacade.search("충전 취소")).willReturn(new FaqSearchDto(List.of()));

        mockMvc.perform(get("/api/v1/ai/faqs/search").param("query", "  충전 취소 ").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.faqs").isArray());
        verify(faqFacade).search("충전 취소");
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/ai/faqs/search").param("query", "충전 취소"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(faqFacade);
    }

    @Test
    @DisplayName("질문이 비었거나 200자를 넘으면 400")
    void invalidQuery() throws Exception {
        for (String query : List.of(" ", "가".repeat(201))) {
            mockMvc.perform(get("/api/v1/ai/faqs/search").param("query", query).header("Authorization", bearer()))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/ai/faqs/search").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(faqFacade);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(3L, MemberRole.USER);
    }
}
