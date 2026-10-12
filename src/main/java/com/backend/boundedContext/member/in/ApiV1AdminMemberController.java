package com.backend.boundedContext.member.in;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 회원", description = "회원 목록·상세 조회")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class ApiV1AdminMemberController {

    private final MemberFacade memberFacade;

    @Operation(summary = "회원 목록 조회", description = "가입일 최신순으로 고정(sort 값은 무시). 탈퇴 회원도 포함합니다. page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<AdminMemberDto>> getMembers(
            @Parameter(description = "이메일 부분 검색") @RequestParam(required = false) String email,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) MemberRole role,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "회원 목록을 조회했습니다.", memberFacade.getMembers(email, status, role, pageable));
    }

    @Operation(summary = "회원 상세 조회", description = "역할, 판매자 상태(신청 이력이 없으면 null), 지갑 잔액을 함께 반환합니다.")
    @GetMapping("/{userId}")
    public RsData<AdminMemberDetailDto> getMemberDetail(
            @PathVariable Long userId
    ) {
        return RsData.of("200-1", "회원 상세를 조회했습니다.", memberFacade.getMemberDetail(userId));
    }
}
