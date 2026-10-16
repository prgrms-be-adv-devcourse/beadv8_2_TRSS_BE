package com.backend.boundedContext.member.app.dto;

import com.backend.global.page.PageResponse;

// 관리자 판매자 목록 응답
public record AdminSellerListDto(
        PageResponse<AdminSellerDto> sellers,
        AdminSellerCountsDto counts
) {
}