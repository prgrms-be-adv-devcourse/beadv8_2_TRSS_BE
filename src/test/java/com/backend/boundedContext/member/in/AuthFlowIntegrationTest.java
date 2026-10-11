package com.backend.boundedContext.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.RefreshTokenStore;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * 로그인 → 재발급(회전) → 로그아웃 흐름을 실제 Redis·PostgreSQL로 확인한다. 로컬 Docker가 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "palette123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenStore refreshTokenStore;

    private final String email = "auth-it-" + UUID.randomUUID() + "@palette.com";

    // 테스트 중 발급된 Refresh Token (끝나면 Redis에서 지운다)
    private final List<String> issuedRefreshTokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memberRepository.save(Member.signUp(email, passwordEncoder.encode(PASSWORD), "홍길동"));
    }

    @AfterEach
    void cleanUp() {
        issuedRefreshTokens.forEach(refreshTokenStore::delete);
        memberRepository.findByEmail(email).ifPresent(memberRepository::delete);
    }

    private MvcResult login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        recordIssuedRefreshToken(result);
        return result;
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refreshToken", refreshToken))).andReturn();
        recordIssuedRefreshToken(result);
        return result;
    }

    private void recordIssuedRefreshToken(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refreshToken");
        if (cookie != null && !cookie.getValue().isBlank()) {
            issuedRefreshTokens.add(cookie.getValue());
        }
    }

    @Test
    @DisplayName("재발급하면 새 Refresh Token이 나오고, 이전 Refresh Token으로는 다시 재발급할 수 없다")
    void refreshRotation() throws Exception {
        String oldRefresh = login().getResponse().getCookie("refreshToken").getValue();

        MvcResult refreshed = refresh(oldRefresh);
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        String newRefresh = refreshed.getResponse().getCookie("refreshToken").getValue();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        MvcResult reused = refresh(oldRefresh);
        assertThat(reused.getResponse().getStatus()).isEqualTo(401);
        assertThat(reused.getResponse().getContentAsString()).contains("401-AUTH_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("로그아웃하면 그 Refresh Token으로 재발급할 수 없다")
    void logoutRevokesRefreshToken() throws Exception {
        MvcResult loggedIn = login();
        String refreshToken = loggedIn.getResponse().getCookie("refreshToken").getValue();
        String accessToken = JsonPath.read(loggedIn.getResponse().getContentAsString(), "$.data.accessToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-AUTH_TOKEN_EXPIRED"));
    }
}
