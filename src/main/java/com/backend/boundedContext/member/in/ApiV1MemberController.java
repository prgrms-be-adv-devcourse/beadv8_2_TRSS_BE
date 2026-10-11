package com.backend.boundedContext.member.in;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "회원", description = "내 정보")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class ApiV1MemberController {

    private final MemberFacade memberFacade;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public RsData<MyInfoDto> getMyInfo(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "내 정보를 조회했습니다.", memberFacade.getMyInfo(authMember.memberId()));
    }
}
