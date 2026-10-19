package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import com.backend.boundedContext.member.domain.MemberType;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerStatus;

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

    @Autowired
    private SellerRepository sellerRepository;

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
        sellerRepository.deleteAll(sellerRepository.findAllByMemberIdIn(saved.stream().map(Member::getId).toList()));
        memberRepository.deleteAll(saved);
    }

    @Test
    @DisplayName("판매자 신청 이력이 있으면 판매자 상태를 함께 주고, 없으면 null")
    void sellerStatus() {
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        sellerRepository.save(Seller.apply(saved.get(0), "A상회", businessNumber, "홍길동",
                "01012345678", "국민은행", "123456-01-123456", "홍길동"));

        PageResponse<AdminMemberDto> result = memberFacade.getMembers(keyword, null, null, PageRequest.of(0, 20));

        assertThat(result.items())
                .extracting(AdminMemberDto::name, AdminMemberDto::sellerStatus)
                .containsExactly(tuple("회원C", null), tuple("회원B", null), tuple("회원A", SellerStatus.PENDING));
    }

    @Test
    @DisplayName("이메일 부분 검색: 대소문자와 상관없이 찾고, 탈퇴 회원도 포함하며 가입일 최신순으로 정렬한다")
    void searchByEmail() {
        PageResponse<AdminMemberDto> result = memberFacade.getMembers(keyword.toUpperCase(), null, null, PageRequest.of(0, 20));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.items()).extracting(AdminMemberDto::name).containsExactly("회원C", "회원B", "회원A");
    }

    @Test
    @DisplayName("상태·회원 유형 필터를 함께 걸 수 있다")
    void filterByStatusAndMemberType() {
        assertThat(memberFacade.getMembers(keyword, MemberStatus.DELETED, null, PageRequest.of(0, 20)).items())
                .extracting(AdminMemberDto::name).containsExactly("회원C");
        assertThat(memberFacade.getMembers(keyword, MemberStatus.ACTIVE, MemberType.ADMIN, PageRequest.of(0, 20)).items())
                .extracting(AdminMemberDto::name).containsExactly("회원B");
    }

    @Test
    @DisplayName("회원 유형: 판매 중(APPROVED)인 회원만 판매자이고, 심사 중·제재 상태는 일반 회원에 포함된다")
    void filterByMemberType() {
        Member approved = memberRepository.save(Member.signUp(keyword + "-d@palette.test", "encoded", "회원D"));
        Member pending = memberRepository.save(Member.signUp(keyword + "-e@palette.test", "encoded", "회원E"));
        Member sanctioned = memberRepository.save(Member.signUp(keyword + "-f@palette.test", "encoded", "회원F"));
        saved.addAll(List.of(approved, pending, sanctioned));
        Seller approvedSeller = applySeller(approved);
        approvedSeller.approve();
        sellerRepository.save(approvedSeller);
        sellerRepository.save(applySeller(pending));
        Seller sanctionedSeller = applySeller(sanctioned);
        sanctionedSeller.approve();
        sanctionedSeller.sanction("위조 상품 판매");
        sellerRepository.save(sanctionedSeller);

        assertThat(memberFacade.getMembers(keyword, null, MemberType.SELLER, PageRequest.of(0, 20)).items())
                .extracting(AdminMemberDto::name, AdminMemberDto::memberType).containsExactly(tuple("회원D", MemberType.SELLER));
        PageResponse<AdminMemberDto> users = memberFacade.getMembers(keyword, null, MemberType.USER, PageRequest.of(0, 20));
        assertThat(users.items()).extracting(AdminMemberDto::name).containsExactly("회원F", "회원E", "회원C", "회원A");
        assertThat(users.items()).extracting(AdminMemberDto::memberType).containsOnly(MemberType.USER);
        assertThat(users.totalElements()).isEqualTo(4);
        assertThat(memberFacade.getMembers(keyword, null, MemberType.ADMIN, PageRequest.of(0, 20)).items())
                .extracting(AdminMemberDto::name, AdminMemberDto::memberType).containsExactly(tuple("회원B", MemberType.ADMIN));
    }

    private Seller applySeller(Member member) {
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        return Seller.apply(member, member.getName() + " 상회", businessNumber, "홍길동",
                "01012345678", "국민은행", "123456-01-123456", "홍길동");
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
