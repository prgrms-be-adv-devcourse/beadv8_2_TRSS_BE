package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1AdminInfluencerController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminInfluencerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberInfluencerFacade memberInfluencerFacade;

    @Test
    @DisplayName("관리자가 아니면 403")
    void notAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/influencers").header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("반려·해제 사유가 비면 400")
    void blankReason() throws Exception {
        for (String action : new String[]{"reject", "revoke"}) {
            mockMvc.perform(post("/api/v1/admin/influencers/7/" + action)
                            .header("Authorization", "Bearer " + token(MemberRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason": " "}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    private String token(MemberRole role) {
        return jwtTokenProvider.createAccessToken(1L, role);
    }
}
