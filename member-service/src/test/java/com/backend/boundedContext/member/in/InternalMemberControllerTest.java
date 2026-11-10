package com.backend.boundedContext.member.in;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

/**
 * 회원 내부 API의 요청·응답 모양을 확인한다.
 * /internal 접근 제한(보안 설정)은 이 테스트 범위가 아니라서 보안 필터는 끈다.
 */
@WebMvcTest(InternalMemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalMemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberFacade memberFacade;

    private static MemberDto member(Long id, String email) {
        return new MemberDto(id, email, "회원" + id, MemberRole.USER, MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("회원 조회: MemberApi.getMember 결과를 RsData로 돌려준다")
    void getMember() throws Exception {
        given(memberFacade.getMember(3L)).willReturn(member(3L, "user3@test.com"));

        mockMvc.perform(get("/internal/v1/members/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.email").value("user3@test.com"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("회원 조회: 없으면 404-MEMBER_NOT_FOUND")
    void getMember_notFound() throws Exception {
        given(memberFacade.getMember(99L)).willThrow(new DomainException(MemberErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/members/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("활성 회원 확인: 활성이면 200, data 없음")
    void checkActiveMember() throws Exception {
        mockMvc.perform(get("/internal/v1/members/3/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));
    }

    @Test
    @DisplayName("활성 회원 확인: 없거나 탈퇴했으면 401-UNAUTHORIZED")
    void checkActiveMember_inactive() throws Exception {
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberFacade).checkActiveMember(3L);

        mockMvc.perform(get("/internal/v1/members/3/active"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    @Test
    @DisplayName("여러 회원 조회: ids를 쉼표로 받아 있는 회원만 돌려준다")
    void getMembers() throws Exception {
        given(memberFacade.getMembersByIds(List.of(1L, 2L, 99L)))
                .willReturn(List.of(member(1L, "user1@test.com"), member(2L, "user2@test.com")));

        mockMvc.perform(get("/internal/v1/members").param("ids", "1,2,99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].id").value(2));
    }

    @Test
    @DisplayName("여러 회원 조회: ids가 없으면 빈 목록")
    void getMembers_empty() throws Exception {
        given(memberFacade.getMembersByIds(List.of())).willReturn(List.of());

        mockMvc.perform(get("/internal/v1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("이메일로 회원 조회: 이메일을 본문으로 받아 회원을 돌려준다")
    void findMemberByEmail() throws Exception {
        given(memberFacade.findMemberByEmail("user3@test.com")).willReturn(Optional.of(member(3L, "user3@test.com")));

        mockMvc.perform(post("/internal/v1/members/search-by-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "user3@test.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3));
    }

    @Test
    @DisplayName("이메일로 회원 조회: 없으면 200, data null")
    void findMemberByEmail_notFound() throws Exception {
        given(memberFacade.findMemberByEmail("none@test.com")).willReturn(Optional.empty());

        mockMvc.perform(post("/internal/v1/members/search-by-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "none@test.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
