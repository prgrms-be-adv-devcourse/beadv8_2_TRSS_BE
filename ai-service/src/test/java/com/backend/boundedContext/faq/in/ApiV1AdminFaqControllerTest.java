package com.backend.boundedContext.faq.in;

import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.AdminFaqDto;
import com.backend.boundedContext.faq.domain.FaqCategory;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1AdminFaqController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminFaqControllerTest {

    private static final AdminFaqDto FAQ = new AdminFaqDto(5L, FaqCategory.TOPUP, "충전", "질문", "답변", true, false,
            LocalDateTime.of(2026, 11, 16, 12, 0), LocalDateTime.of(2026, 11, 16, 12, 0));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private FaqFacade faqFacade;

    @Test
    @DisplayName("관리자는 목록·등록·수정·노출 변경을 할 수 있음")
    void admin() throws Exception {
        given(faqFacade.getAdminList(FaqCategory.TOPUP)).willReturn(List.of(FAQ));
        given(faqFacade.create(FaqCategory.TOPUP, "질문", "답변", true)).willReturn(5L);
        given(faqFacade.modify(5L, FaqCategory.TOPUP, "질문", "새 답변")).willReturn(FAQ);
        given(faqFacade.changeVisibility(5L, false)).willReturn(FAQ);

        mockMvc.perform(get("/api/v1/admin/faqs").param("category", "TOPUP").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryLabel").value("충전"))
                .andExpect(jsonPath("$.data[0].embedded").value(false));
        mockMvc.perform(post("/api/v1/admin/faqs").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"TOPUP","question":"질문","answer":"답변","visible":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.faqId").value(5));
        mockMvc.perform(put("/api/v1/admin/faqs/5").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"TOPUP","question":"질문","answer":"새 답변"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/faqs/5/visibility").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visible":false}"""))
                .andExpect(status().isOk());

        verify(faqFacade).changeVisibility(5L, false);
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인하지 않으면 401")
    void security() throws Exception {
        mockMvc.perform(get("/api/v1/admin/faqs").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/faqs"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(faqFacade);
    }

    @Test
    @DisplayName("빈 질문, 200자 넘는 질문, 없는 카테고리, 노출 여부 누락은 400")
    void invalid() throws Exception {
        List<String> bodies = List.of(
                """
                        {"category":"TOPUP","question":" ","answer":"답변","visible":true}""",
                """
                        {"category":"TOPUP","question":"%s","answer":"답변","visible":true}""".formatted("가".repeat(201)),
                """
                        {"category":"COUPON","question":"질문","answer":"답변","visible":true}""",
                """
                        {"category":"TOPUP","question":"질문","answer":"답변"}""");
        for (String body : bodies) {
            mockMvc.perform(post("/api/v1/admin/faqs").header("Authorization", bearer(MemberRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(faqFacade);
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }
}
