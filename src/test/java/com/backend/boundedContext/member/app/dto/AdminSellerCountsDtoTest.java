package com.backend.boundedContext.member.app.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.member.dto.SellerStatus;

class AdminSellerCountsDtoTest {

    @Test
    @DisplayName("상태별 건수에 없는 상태는 0이고, all은 상태별 건수의 합이다")
    void from_fillsZeroAndSumsAll() {
        AdminSellerCountsDto counts = AdminSellerCountsDto.from(Map.of(
                SellerStatus.PENDING, 3L,
                SellerStatus.APPROVED, 12L,
                SellerStatus.WITHDRAW_REQUESTED, 1L));

        assertThat(counts).isEqualTo(new AdminSellerCountsDto(16, 3, 12, 0, 1, 0, 0));
    }

    @Test
    @DisplayName("판매자가 없으면 모두 0이다")
    void from_empty() {
        assertThat(AdminSellerCountsDto.from(Map.of())).isEqualTo(new AdminSellerCountsDto(0, 0, 0, 0, 0, 0, 0));
    }
}
