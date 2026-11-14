package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.InfluencerStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1InfluencerController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1InfluencerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberInfluencerFacade memberInfluencerFacade;

    @Test
    @DisplayName("신청: 토큰의 회원 ID로 신청하고 201과 신청 정보를 돌려준다")
    void apply_success() throws Exception {
        given(memberInfluencerFacade.applyInfluencer(eq(2L), any(InfluencerApplyRequest.class))).willReturn(new MyInfluencerResponse(
                7L, InfluencerStatus.PENDING, "데일리 뷰티 민지", "https://instagram.com/daily.beauty", "스킨케어 루틴",
                LocalDateTime.of(2026, 11, 12, 10, 0), null, null));

        mockMvc.perform(post("/api/v1/influencers")
                        .header("Authorization", "Bearer " + token(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("데일리 뷰티 민지", "https://instagram.com/daily.beauty", "스킨케어 루틴")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultCode").value("201-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.introduction").value("스킨케어 루틴"));
    }

    @Test
    @DisplayName("신청 입력 규칙: 활동명 31자, http(s)가 아닌 주소, 빈 소개는 400")
    void apply_invalid() throws Exception {
        assertBadRequest(body("가".repeat(31), "https://instagram.com/a", "소개"));
        assertBadRequest(body("민지", "ftp://instagram.com/a", "소개"));
        assertBadRequest(body("민지", "instagram.com/a", "소개"));
        assertBadRequest(body("민지", "https://instagram.com/a", "  "));
    }

    @Test
    @DisplayName("신청: 관리자는 403")
    void apply_admin() throws Exception {
        mockMvc.perform(post("/api/v1/influencers")
                        .header("Authorization", "Bearer " + token(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("민지", "https://instagram.com/a", "소개")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("내 신청 정보: 토큰이 없으면 401 (검색·프로필 공개 규칙에 걸리지 않는다)")
    void getMy_noToken() throws Exception {
        mockMvc.perform(get("/api/v1/influencers/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내 신청 정보: 반려 사유와 결정 시각을 돌려준다")
    void getMy_success() throws Exception {
        given(memberInfluencerFacade.getMyInfluencer(2L)).willReturn(new MyInfluencerResponse(
                7L, InfluencerStatus.REJECTED, "민지", "https://instagram.com/a", "소개",
                LocalDateTime.of(2026, 11, 12, 10, 0), LocalDateTime.of(2026, 11, 12, 11, 0), "본인 확인이 어려워요"));

        mockMvc.perform(get("/api/v1/influencers/me").header("Authorization", "Bearer " + token(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reason").value("본인 확인이 어려워요"))
                .andExpect(jsonPath("$.data.decidedAt").exists());
    }

    @Test
    @DisplayName("프로필: 로그인 없이 볼 수 있다")
    void profile_public() throws Exception {
        given(memberInfluencerFacade.getInfluencerProfile(7L)).willReturn(new InfluencerProfileResponse(7L, "민지", "소개"));

        mockMvc.perform(get("/api/v1/influencers/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityName").value("민지"));
    }

    private void assertBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/influencers")
                        .header("Authorization", "Bearer " + token(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private static String body(String activityName, String snsUrl, String introduction) {
        return """
                {"activityName": "%s", "snsUrl": "%s", "introduction": "%s"}
                """.formatted(activityName, snsUrl, introduction);
    }

    private String token(MemberRole role) {
        return jwtTokenProvider.createAccessToken(2L, role);
    }
}
