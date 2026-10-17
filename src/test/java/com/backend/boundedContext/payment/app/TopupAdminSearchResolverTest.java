package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.out.MemberApi;

@ExtendWith(MockitoExtension.class)
class TopupAdminSearchResolverTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 17);
    private static final String ORDER_ID = "TOPUP-88e21df8-bddb-4ff6-9ea1-dd5e1cc3cc04";

    @Mock
    private MemberApi memberApi;

    @InjectMocks
    private TopupAdminSearchResolver resolver;

    @Test
    @DisplayName("검색어가 비어 있으면 기간만 적용한다")
    void blank() {
        AdminTopupSearch search = resolver.resolve("  ", FROM, TO);

        assertThat(search).isEqualTo(AdminTopupSearch.all(FROM, TO));
        verifyNoInteractions(memberApi);
    }

    @Test
    @DisplayName("@가 있으면 이메일로 회원을 찾아 그 회원 조건으로 조회한다")
    void email_found() {
        given(memberApi.findMemberByEmail("buyer@palette.test")).willReturn(Optional.of(
                new MemberDto(3L, "buyer@palette.test", "구매자", MemberRole.USER, MemberStatus.ACTIVE)));

        AdminTopupSearch search = resolver.resolve(" buyer@palette.test ", FROM, TO);

        assertThat(search).isEqualTo(AdminTopupSearch.ofMember(3L, FROM, TO));
    }

    @Test
    @DisplayName("이메일에 맞는 회원이 없으면 결과 없음")
    void email_notFound() {
        given(memberApi.findMemberByEmail("nobody@palette.test")).willReturn(Optional.empty());

        assertThat(resolver.resolve("nobody@palette.test", FROM, TO)).isEqualTo(AdminTopupSearch.none(FROM, TO));
    }

    @Test
    @DisplayName("TOPUP-으로 시작하면 충전번호 조건으로 조회한다")
    void orderId() {
        AdminTopupSearch search = resolver.resolve(ORDER_ID, FROM, TO);

        assertThat(search).isEqualTo(AdminTopupSearch.ofOrderId(ORDER_ID, FROM, TO));
        verifyNoInteractions(memberApi);
    }

    @Test
    @DisplayName("주문번호(ORD-…)처럼 충전 탭에서 지원하지 않는 검색어면 결과 없음")
    void unsupportedKeyword() {
        assertThat(resolver.resolve("ORD-20261009-0142", FROM, TO)).isEqualTo(AdminTopupSearch.none(FROM, TO));
        verifyNoInteractions(memberApi);
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 400 PAYMENT_PERIOD_INVALID")
    void periodInvalid() {
        assertThatThrownBy(() -> resolver.resolve(null, TO, FROM))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-PAYMENT_PERIOD_INVALID");
    }

    @Test
    @DisplayName("기간을 비워 두면 기간 조건 없이 조회한다")
    void noPeriod() {
        assertThat(resolver.resolve(null, null, null)).isEqualTo(AdminTopupSearch.all(null, null));
    }
}
