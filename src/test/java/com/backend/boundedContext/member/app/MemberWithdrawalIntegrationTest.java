package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.security.JwtTokenProvider;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.member.dto.MemberStatus;

/**
 * 탈퇴 조건 조회 → 탈퇴 → 로그인 불가를 실제 DB·Redis로 확인한다. 로컬 Docker가 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberWithdrawalIntegrationTest {

    private static final String PASSWORD = "palette123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // 주문은 market-service로 옮겨 갔다. 루트의 임시 구현은 예외를 던지므로 목으로 둔다
    @MockitoBean
    private OrderApi orderApi;

    @Autowired
    private LoginAttemptStore loginAttemptStore;

    @Autowired
    private MemberSocialAccountLinker memberSocialAccountLinker;

    @Autowired
    private MemberSocialAccountRepository memberSocialAccountRepository;

    private final String email = "withdraw-it-" + UUID.randomUUID() + "@palette.com";
    private Member member;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.signUp(email, passwordEncoder.encode(PASSWORD), "홍길동"));
        paymentFacade.createWallet(member.getId());
    }

    @AfterEach
    void cleanUp() {
        loginAttemptStore.deleteAttempts(email);
        memberSocialAccountRepository.findByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO)
                .ifPresent(memberSocialAccountRepository::delete);
        walletRepository.findByMemberId(member.getId()).ifPresent(walletRepository::delete);
        memberRepository.deleteById(member.getId());
    }

    @Test
    @DisplayName("조건을 충족하면 탈퇴되고, 개인정보가 비식별화되어 원래 이메일로 로그인할 수 없다. 카카오 연결 정보도 지워진다")
    void withdraw() throws Exception {
        String kakaoUserId = "test-" + UUID.randomUUID();
        memberSocialAccountLinker.link(member.getId(), SocialProvider.KAKAO, kakaoUserId);
        String bearer = "Bearer " + jwtTokenProvider.createAccessToken(member.getId(), member.getRole());

        mockMvc.perform(get("/api/v1/users/me/withdrawal")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawable").value(true));

        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk());

        Member withdrawn = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(MemberStatus.DELETED);
        assertThat(withdrawn.getEmail()).isEqualTo("deleted-" + member.getId() + "@deleted.palette");
        assertThat(withdrawn.getName()).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat(memberRepository.findByEmail(email)).isEmpty();
        // 탈퇴한 회원의 카카오 계정으로는 로그인할 수 없고, 같은 카카오 계정을 새 회원에게 다시 연결할 수 있다
        assertThat(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, kakaoUserId)).isEmpty();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-LOGIN_FAILED"));

        // 탈퇴 전에 받은 Access Token은 아직 만료되지 않았지만, 돈이 들어가는 충전은 막힌다
        mockMvc.perform(post("/api/v1/topups").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"amount": 10000}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 400 PASSWORD_MISMATCH이고 탈퇴되지 않는다")
    void withdraw_wrongPassword() throws Exception {
        String bearer = "Bearer " + jwtTokenProvider.createAccessToken(member.getId(), member.getRole());

        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"password": "wrong123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-PASSWORD_MISMATCH"));

        assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
