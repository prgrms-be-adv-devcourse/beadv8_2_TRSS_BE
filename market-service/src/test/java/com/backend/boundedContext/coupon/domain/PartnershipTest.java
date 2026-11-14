package com.backend.boundedContext.coupon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartnershipTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);

    private Partnership requested() {
        return Partnership.request(20L, 30L, 20, "함께해요", NOW);
    }

    @Test
    @DisplayName("협약을 요청하면 REQUESTED와 요청 시각, 메시지가 남는다")
    void request() {
        Partnership partnership = requested();

        assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.REQUESTED);
        assertThat(partnership.getSellerId()).isEqualTo(20L);
        assertThat(partnership.getInfluencerId()).isEqualTo(30L);
        assertThat(partnership.getRequestMessage()).isEqualTo("함께해요");
        assertThat(partnership.getRequestedAt()).isEqualTo(NOW);
        assertThat(partnership.isOpen()).isTrue();
        assertThat(partnership.isActive()).isFalse();
    }

    @Test
    @DisplayName("수락하면 ACTIVE, 거절하면 REJECTED가 되고 시각이 남는다")
    void acceptAndReject() {
        Partnership accepted = requested();
        Partnership rejected = requested();

        accepted.accept(NOW.plusHours(1));
        rejected.reject(NOW.plusHours(2));

        assertThat(accepted.getStatus()).isEqualTo(PartnershipStatus.ACTIVE);
        assertThat(accepted.getAcceptedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(accepted.isActive()).isTrue();
        assertThat(rejected.getStatus()).isEqualTo(PartnershipStatus.REJECTED);
        assertThat(rejected.getRejectedAt()).isEqualTo(NOW.plusHours(2));
        assertThat(rejected.isOpen()).isFalse();
    }

    @Test
    @DisplayName("진행 중 협약을 종료하면 ENDED와 종료 주체, 시각이 남는다")
    void end() {
        Partnership partnership = requested();
        partnership.accept(NOW);

        partnership.end(PartnershipEndedBy.INFLUENCER, NOW.plusDays(1));

        assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.ENDED);
        assertThat(partnership.getEndedBy()).isEqualTo(PartnershipEndedBy.INFLUENCER);
        assertThat(partnership.getEndedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(partnership.isOpen()).isFalse();
    }

    @Test
    @DisplayName("요청 중이 아닌 협약의 수락, 거절과 진행 중이 아닌 협약의 종료는 409-PARTNERSHIP_STATE_INVALID")
    void stateInvalid() {
        Partnership active = requested();
        active.accept(NOW);
        Partnership pending = requested();

        assertThatThrownBy(() -> active.accept(NOW)).extracting("resultCode").isEqualTo("409-PARTNERSHIP_STATE_INVALID");
        assertThatThrownBy(() -> active.reject(NOW)).extracting("resultCode").isEqualTo("409-PARTNERSHIP_STATE_INVALID");
        assertThatThrownBy(() -> pending.end(PartnershipEndedBy.SELLER, NOW))
                .extracting("resultCode").isEqualTo("409-PARTNERSHIP_STATE_INVALID");
    }

    @Test
    @DisplayName("판매자 제재로 자동 종료하면 요청 중 협약도 SYSTEM 종료되고, 이미 닫힌 협약은 409")
    void endBySystem() {
        Partnership pending = requested();

        pending.endBySystem(NOW);

        assertThat(pending.getStatus()).isEqualTo(PartnershipStatus.ENDED);
        assertThat(pending.getEndedBy()).isEqualTo(PartnershipEndedBy.SYSTEM);
        assertThatThrownBy(() -> pending.endBySystem(NOW)).extracting("resultCode").isEqualTo("409-PARTNERSHIP_STATE_INVALID");
    }

    @Test
    @DisplayName("판매자, 인플루언서 소유 확인")
    void ownership() {
        Partnership partnership = requested();

        assertThat(partnership.isOwnedBySeller(20L)).isTrue();
        assertThat(partnership.isOwnedBySeller(21L)).isFalse();
        assertThat(partnership.isForInfluencer(30L)).isTrue();
        assertThat(partnership.isForInfluencer(31L)).isFalse();
    }
}
