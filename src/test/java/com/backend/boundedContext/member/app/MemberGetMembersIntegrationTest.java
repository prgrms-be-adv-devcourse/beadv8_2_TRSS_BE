package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

/**
 * 관리자 회원 목록의 검색·필터·정렬·페이징을 실제 PostgreSQL로 확인한다. 로컬 Docker가 필요하다.
 * 다른 데이터와 섞이지 않도록 이번 테스트만의 이메일 키워드로 검색한다.
 */
@SpringBootTest
class MemberGetMembersIntegrationTest {

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    private final String keyword = "list-it-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Member> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        save(Member.signUp(keyword + "-a@palette.test", "encoded", "회원A"), MemberStatus.ACTIVE);
        save(Member.admin(keyword + "-b@palette.test", "encoded", "회원B"), MemberStatus.ACTIVE);
        save(Member.signUp(keyword + "-c@palette.test", "encoded", "회원C"), MemberStatus.DELETED);
    }

    private void save(Member member, MemberStatus status) {
        ReflectionTestUtils.setField(member, "status", status);
        saved.add(memberRepository.save(member));
    }

    @AfterEach
    void cleanUp() {
        memberRepository.deleteAll(saved);
    }

    @Test
    @DisplayName("이메일 부분 검색: 대소문자와 상관없이 찾고, 탈퇴 회원도 포함하며 가입일 최신순으로 정렬한다")
    void searchByEmail() {
        PageResponse<AdminMemberDto> result = memberFacade.getMembers(keyword.toUpperCase(), null, null, PageRequest.of(0, 20));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.items()).extracting(AdminMemberDto::name).containsExactly("회원C", "회원B", "회원A");
    }

    @Test
    @DisplayName("상태·역할 필터를 함께 걸 수 있다")
    void filterByStatusAndRole() {
        assertThat(memberFacade.getMembers(keyword, MemberStatus.DELETED, null, PageRequest.of(0, 20)).items())
                .extracting(AdminMemberDto::name).containsExactly("회원C");
        assertThat(memberFacade.getMembers(keyword, MemberStatus.ACTIVE, MemberRole.ADMIN, PageRequest.of(0, 20)).items())
                .extracting(AdminMemberDto::name).containsExactly("회원B");
    }

    @Test
    @DisplayName("페이지 크기만큼 나눠 조회한다")
    void paging() {
        PageResponse<AdminMemberDto> second = memberFacade.getMembers(keyword, null, null, PageRequest.of(1, 2));

        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(second.items()).extracting(AdminMemberDto::name).containsExactly("회원A");
    }
}
