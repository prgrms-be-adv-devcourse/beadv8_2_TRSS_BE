package com.backend.boundedContext.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.backend.boundedContext.member.app.MemberSocialAccountLinker;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.member.out.RefreshTokenStore;
import com.backend.boundedContext.member.out.kakao.KakaoOAuthClient;
import com.backend.boundedContext.member.out.kakao.KakaoUser;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * 카카오 로그인 API를 실제 DB·Redis와 보안 설정까지 거쳐 확인한다 (카카오 API만 mock). 로컬 Docker가 필요하다.
 * 로그인 전 요청이라 인증 없이 호출할 수 있어야 하고(공개 경로), 응답은 이메일 로그인과 같아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KakaoLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberSocialAccountRepository memberSocialAccountRepository;

    @Autowired
    private MemberSocialAccountLinker memberSocialAccountLinker;

    @Autowired
    private RefreshTokenStore refreshTokenStore;

    @MockitoBean
    private KakaoOAuthClient kakaoOAuthClient;

    private static final String IMAGE_URL = "https://k.kakaocdn.net/dn/abc/img_640x640.jpg";
    private static final String NEW_IMAGE_URL = "https://k.kakaocdn.net/dn/xyz/img_640x640.jpg";

    private final String kakaoUserId = "test-" + UUID.randomUUID();
    private Member member;
    private String issuedRefreshToken;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.signUp("kakao-login-it-" + UUID.randomUUID() + "@palette.com", "encoded", "회원"));
    }

    @AfterEach
    void cleanUp() {
        if (issuedRefreshToken != null) refreshTokenStore.delete(issuedRefreshToken);
        memberSocialAccountRepository.findByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO)
                .ifPresent(memberSocialAccountRepository::delete);
        memberRepository.delete(member);
    }

    @Test
    @DisplayName("연결된 카카오 계정이면 이메일 로그인과 같이 Access Token(바디)과 Refresh Token(쿠키)을 발급한다")
    void kakaoLogin_linked() throws Exception {
        memberSocialAccountLinker.link(member.getId(), SocialProvider.KAKAO, kakaoUserId, null);
        given(kakaoOAuthClient.getUser("auth-code")).willReturn(new KakaoUser(kakaoUserId, null));

        MvcResult result = kakaoLogin("auth-code")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        Cookie refreshCookie = result.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        issuedRefreshToken = refreshCookie.getValue();
    }

    @Test
    @DisplayName("연결되지 않은 카카오 계정이면 가입시키지 않고 401 SOCIAL_NOT_LINKED")
    void kakaoLogin_notLinked() throws Exception {
        given(kakaoOAuthClient.getUser("auth-code")).willReturn(new KakaoUser(kakaoUserId, IMAGE_URL));

        kakaoLogin("auth-code")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-SOCIAL_NOT_LINKED"));
    }

    @Test
    @DisplayName("카카오 로그인 때 최신 프로필 이미지로 갱신돼 내 정보에 담기고, 연결을 해제하면 null이 된다")
    void kakaoLogin_updatesProfileImage() throws Exception {
        memberSocialAccountLinker.link(member.getId(), SocialProvider.KAKAO, kakaoUserId, IMAGE_URL);
        given(kakaoOAuthClient.getUser("auth-code")).willReturn(new KakaoUser(kakaoUserId, NEW_IMAGE_URL));

        MvcResult result = kakaoLogin("auth-code").andExpect(status().isOk()).andReturn();
        issuedRefreshToken = result.getResponse().getCookie("refreshToken").getValue();
        String bearer = "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kakaoLinked").value(true))
                .andExpect(jsonPath("$.data.profileImageUrl").value(NEW_IMAGE_URL));

        mockMvc.perform(delete("/api/v1/users/me/social/kakao").header("Authorization", bearer))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.data.kakaoLinked").value(false))
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }

    private ResultActions kakaoLogin(String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "%s"}
                        """.formatted(code)));
    }
}
