package com.backend.boundedContext.member.in;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.in.dto.WithdrawalRequest;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "회원", description = "내 정보, 탈퇴")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class ApiV1MemberController {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookie refreshTokenCookie;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public RsData<MyInfoDto> getMyInfo(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "내 정보를 조회했습니다.", memberFacade.getMyInfo(authMember.memberId()));
    }

    @Operation(summary = "탈퇴 조건 조회", description = "진행 중인 주문 여부, 정산받지 않은 판매 금액, 남은 예치금을 돌려준다. reasons가 비어 있으면(withdrawable: true) 탈퇴할 수 있다")
    @GetMapping("/me/withdrawal")
    public RsData<WithdrawalCheckDto> getWithdrawalCheck(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "탈퇴 조건을 조회했습니다.", memberFacade.getWithdrawalCheck(authMember.memberId()));
    }

    @Operation(summary = "회원 탈퇴", description = "비밀번호로 본인을 확인하고 탈퇴 조건을 다시 확인한 뒤 탈퇴한다. 조건이 남아 있으면 409 WITHDRAWAL_NOT_ALLOWED(data.reasons)")
    @DeleteMapping("/me")
    public RsData<Void> withdraw(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody WithdrawalRequest request,
            HttpServletResponse response
    ) {
        memberFacade.withdraw(authMember.memberId(), request.password());
        refreshTokenCookie.clear(response);
        return RsData.of("200-1", "탈퇴했습니다.");
    }
}
