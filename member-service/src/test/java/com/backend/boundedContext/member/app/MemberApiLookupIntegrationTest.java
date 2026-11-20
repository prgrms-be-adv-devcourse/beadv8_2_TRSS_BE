package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.out.MemberApi;

/**
 * MemberApi의 여러 회원 조회·이메일 조회를 실제 PostgreSQL로 확인한다. 로컬 Docker가 필요하다.
 */
@SpringBootTest
class MemberApiLookupIntegrationTest {

    @Autowired
    private MemberApi memberApi;

    @Autowired
    private MemberRepository memberRepository;

    private final String keyword = "lookup-it-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Member> saved = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberRepository.deleteAll(saved);
    }

    @Test
    @DisplayName("여러 회원 ID로 회원을 한 번에 조회하고, 없는 ID는 결과에서 빠진다")
    void getMembers() {
        Member a = save(keyword + "-a@palette.test");
        Member b = save(keyword + "-b@palette.test");

        List<MemberDto> result = memberApi.getMembers(List.of(a.getId(), b.getId(), -999L));

        assertThat(result).extracting(MemberDto::id, MemberDto::email)
                .containsExactlyInAnyOrder(
                        tuple(a.getId(), a.getEmail()),
                        tuple(b.getId(), b.getEmail()));
        assertThat(memberApi.getMembers(List.of())).isEmpty();
    }

    @Test
    @DisplayName("이메일이 정확히 일치하면 회원을 돌려주고, 일부만 같거나 없으면 empty")
    void findMemberByEmail() {
        Member member = save(keyword + "-a@palette.test");

        assertThat(memberApi.findMemberByEmail(member.getEmail())).map(MemberDto::id).contains(member.getId());
        assertThat(memberApi.findMemberByEmail(keyword)).isEmpty();
    }

    @Test
    @DisplayName("다른 컨텍스트가 대소문자·앞뒤 공백이 섞인 이메일로 조회해도 정규화해서 찾는다 (관리자 결제·충전 검색)")
    void findMemberByEmail_normalized() {
        Member member = save(keyword + "-a@palette.test");

        assertThat(memberApi.findMemberByEmail("  " + member.getEmail().toUpperCase(Locale.ROOT) + " "))
                .map(MemberDto::id).contains(member.getId());
    }

    private Member save(String email) {
        Member member = memberRepository.save(Member.signUp(email, "encoded", "회원"));
        saved.add(member);
        return member;
    }
}
