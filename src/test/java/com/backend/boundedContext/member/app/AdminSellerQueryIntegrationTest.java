package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminSellerCountsDto;
import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminSellerDto;
import com.backend.boundedContext.member.app.dto.AdminSellerListDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 판매자 목록·상세 조회. 로컬 Docker PostgreSQL·Redis가 필요하다.
 * DB에 다른 판매자가 있을 수 있어서, 목록은 이번 테스트가 만든 판매자들 사이의 순서만 확인한다.
 */
@SpringBootTest
class AdminSellerQueryIntegrationTest {

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    private final List<Long> memberIds = new ArrayList<>();

    private Long approvedOld;
    private Long pendingOld;
    private Long approvedNew;
    private Long pendingNew;

    // 신청 순서: APPROVED(오래됨) → PENDING(오래됨) → APPROVED(최근) → PENDING(최근)
    @BeforeEach
    void setUp() {
        approvedOld = apply("A상회");
        approve(approvedOld);
        pendingOld = apply("B상회");
        approvedNew = apply("C상회");
        approve(approvedNew);
        pendingNew = apply("D상회");
    }

    @AfterEach
    void cleanUp() {
        for (Long memberId : memberIds) {
            sellerRepository.findByMemberId(memberId).ifPresent(seller -> {
                sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()));
                sellerRepository.delete(seller);
            });
            memberRepository.deleteById(memberId);
        }
    }

    @Test
    @DisplayName("목록: PENDING이 먼저 나오고, 같은 그룹 안에서는 최근 신청순이다")
    void getSellers_pendingFirst() {
        List<Long> ids = allSellerIds(null);

        assertThat(ids).containsSubsequence(pendingNew, pendingOld, approvedNew, approvedOld);
    }

    @Test
    @DisplayName("목록: 상태 필터를 걸면 그 상태만 나온다")
    void getSellers_statusFilter() {
        PageResponse<AdminSellerDto> result = memberSellerFacade.getSellersForAdmin(SellerStatus.PENDING, PageRequest.of(0, 100)).sellers();

        assertThat(result.items()).extracting(AdminSellerDto::status).containsOnly(SellerStatus.PENDING);
        assertThat(result.items()).extracting(AdminSellerDto::sellerId)
                .contains(pendingNew, pendingOld)
                .doesNotContain(approvedNew, approvedOld);

        AdminSellerDto item = result.items().stream().filter(s -> s.sellerId().equals(pendingNew)).findFirst().orElseThrow();
        assertThat(item.businessName()).isEqualTo("D상회");
        assertThat(item.businessNumber()).hasSize(10);
        assertThat(item.email()).startsWith("admin-seller-query-it-");
    }

    @Test
    @DisplayName("목록: 페이지 크기만큼 나눠 조회한다")
    void getSellers_paging() {
        PageResponse<AdminSellerDto> first = memberSellerFacade.getSellersForAdmin(null, PageRequest.of(0, 2)).sellers();

        assertThat(first.items()).hasSize(2);
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.totalElements()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("건수: 판매자가 늘면 그 상태와 전체(all)만 늘고, all은 상태별 건수의 합이다")
    void getSellers_counts() {
        AdminSellerCountsDto before = counts(null);

        apply("E상회");
        AdminSellerCountsDto after = counts(null);

        assertThat(after.pending()).isEqualTo(before.pending() + 1);
        assertThat(after.all()).isEqualTo(before.all() + 1);
        assertThat(after.approved()).isEqualTo(before.approved());
        assertThat(after.all()).isEqualTo(after.pending() + after.approved() + after.rejected()
                + after.withdrawRequested() + after.withdrawn() + after.sanctioned());
    }

    @Test
    @DisplayName("건수: 상태 필터와 관계없이 전체 판매자 기준이고, 전체 건수는 전체 목록의 totalElements와 같다")
    void getSellers_countsIgnoreStatusFilter() {
        AdminSellerListDto all = memberSellerFacade.getSellersForAdmin(null, PageRequest.of(0, 1));
        AdminSellerListDto pendingOnly = memberSellerFacade.getSellersForAdmin(SellerStatus.PENDING, PageRequest.of(0, 1));

        assertThat(pendingOnly.counts()).isEqualTo(all.counts());
        assertThat(all.counts().all()).isEqualTo(all.sellers().totalElements());
        assertThat(all.counts().pending()).isEqualTo(pendingOnly.sellers().totalElements());
    }

    @Test
    @DisplayName("상세: 판매자 정보, 회원 정보, 마스킹된 계좌, 이력(오래된 순)을 돌려준다")
    void getSellerDetail() {
        AdminSellerDetailDto detail = memberSellerFacade.getSellerDetail(approvedOld);

        assertThat(detail.sellerId()).isEqualTo(approvedOld);
        assertThat(detail.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(detail.businessName()).isEqualTo("A상회");
        assertThat(detail.email()).startsWith("admin-seller-query-it-");
        assertThat(detail.accountNumber()).endsWith("3456").doesNotContain("123456-01");
        assertThat(detail.histories()).extracting(AdminSellerDetailDto.History::toStatus)
                .containsExactly(SellerStatus.PENDING, SellerStatus.APPROVED);
        assertThat(detail.histories().get(0).fromStatus()).isNull();
    }

    @Test
    @DisplayName("상세: 판매자가 없으면 404 SELLER_NOT_FOUND")
    void getSellerDetail_notFound() {
        assertThatThrownBy(() -> memberSellerFacade.getSellerDetail(Long.MAX_VALUE))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_FOUND.resultCode()));
    }

    private List<Long> allSellerIds(SellerStatus status) {
        List<Long> ids = new ArrayList<>();
        int page = 0;
        PageResponse<AdminSellerDto> result;
        do {
            result = memberSellerFacade.getSellersForAdmin(status, PageRequest.of(page++, 100)).sellers();
            result.items().forEach(item -> ids.add(item.sellerId()));
        } while (page < result.totalPages());
        return ids;
    }

    private AdminSellerCountsDto counts(SellerStatus status) {
        return memberSellerFacade.getSellersForAdmin(status, PageRequest.of(0, 1)).counts();
    }

    private Long apply(String businessName) {
        String email = "admin-seller-query-it-" + UUID.randomUUID() + "@palette.com";
        Long memberId = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        memberIds.add(memberId);
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        return memberSellerFacade.applySeller(memberId, new SellerApplyRequest(businessName, businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동")).sellerId();
    }

    // 승인 API는 다음 단계에서 만들어서 지금은 도메인 메서드로 승인한다
    private void approve(Long sellerId) {
        Seller seller = sellerRepository.findById(sellerId).orElseThrow();
        SellerStatusHistory history = seller.approve();
        sellerRepository.saveAndFlush(seller);
        sellerStatusHistoryRepository.save(history);
    }
}
