package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.out.MemberApi;

class PaymentGetAdminTopupsUseCaseTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 17);

    private final TopupAdminSearchResolver searchResolver = mock(TopupAdminSearchResolver.class);
    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final MemberApi memberApi = mock(MemberApi.class);
    private final PaymentGetAdminTopupsUseCase useCase =
            new PaymentGetAdminTopupsUseCase(searchResolver, topupRepository, memberApi);

    @Test
    @DisplayName("검색 조건으로 최신순 조회하고, 페이지에 나온 회원 이메일을 한 번에 모아 붙인다")
    @SuppressWarnings("unchecked")
    void getTopups() {
        given(searchResolver.resolve("buyer@palette.test", FROM, TO)).willReturn(AdminTopupSearch.ofMember(3L, FROM, TO));
        Topup first = Topup.create(3L, Wallet.create(3L), 20_000);
        Topup second = Topup.create(3L, Wallet.create(3L), 10_000);
        given(topupRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(first, second), invocation.getArgument(1), 2));
        given(memberApi.getMembers(List.of(3L))).willReturn(List.of(
                new MemberDto(3L, "buyer@palette.test", "구매자", MemberRole.USER, MemberStatus.ACTIVE)));

        PageResponse<AdminTopupDto> result = useCase.getTopups("buyer@palette.test", FROM, TO, PageRequest.of(1, 10, Sort.by("amount")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(topupRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        verify(memberApi).getMembers(List.of(3L));   // 같은 회원은 한 번만
        assertThat(result.items()).extracting(AdminTopupDto::memberEmail).containsOnly("buyer@palette.test");
        assertThat(result.items()).extracting(AdminTopupDto::amount).containsExactly(20_000L, 10_000L);
    }

    @Test
    @DisplayName("검색어에 맞는 대상이 없으면 조회하지 않고 빈 페이지")
    @SuppressWarnings("unchecked")
    void noResult() {
        given(searchResolver.resolve("ORD-20261009-0142", FROM, TO)).willReturn(AdminTopupSearch.none(FROM, TO));

        PageResponse<AdminTopupDto> result = useCase.getTopups("ORD-20261009-0142", FROM, TO, PageRequest.of(0, 20));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verify(topupRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verifyNoInteractions(memberApi);
    }

    @Test
    @DisplayName("결과가 없으면 회원 조회도 하지 않는다")
    @SuppressWarnings("unchecked")
    void emptyPage() {
        given(searchResolver.resolve(null, null, null)).willReturn(AdminTopupSearch.all(null, null));
        given(topupRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        useCase.getTopups(null, null, null, PageRequest.of(0, 20));

        verify(memberApi, never()).getMembers(anyCollection());
    }
}
