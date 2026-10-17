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
class PaymentAdminSearchResolverTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 17);

    @Mock
    private MemberApi memberApi;

    @InjectMocks
    private PaymentAdminSearchResolver resolver;

    @Test
    @DisplayName("검색어가 비어 있으면 기간만 적용한다")
    void blank() {
        AdminPaymentSearch search = resolver.resolve("  ", FROM, TO);

        assertThat(search).isEqualTo(AdminPaymentSearch.all(FROM, TO));
        verifyNoInteractions(memberApi);
    }

    @Test
    @DisplayName("@가 있으면 이메일로 회원을 찾아 그 회원 조건으로 조회한다")
    void email_found() {
        given(memberApi.findMemberByEmail("buyer@palette.test")).willReturn(Optional.of(
                new MemberDto(3L, "buyer@palette.test", "구매자", MemberRole.USER, MemberStatus.ACTIVE)));

        AdminPaymentSearch search = resolver.resolve("buyer@palette.test", FROM, TO);

        assertThat(search).isEqualTo(AdminPaymentSearch.ofMember(3L, FROM, TO));
    }

    @Test
    @DisplayName("이메일에 맞는 회원이 없거나, 주문번호처럼 지원하지 않는 검색어면 결과 없음")
    void noMatch() {
        given(memberApi.findMemberByEmail("nobody@palette.test")).willReturn(Optional.empty());

        assertThat(resolver.resolve("nobody@palette.test", FROM, TO)).isEqualTo(AdminPaymentSearch.none(FROM, TO));
        assertThat(resolver.resolve("ORD-20261009-0142", FROM, TO)).isEqualTo(AdminPaymentSearch.none(FROM, TO));
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 400 PAYMENT_PERIOD_INVALID")
    void invalidPeriod() {
        assertThatThrownBy(() -> resolver.resolve(null, TO, FROM))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-PAYMENT_PERIOD_INVALID");
    }
}
