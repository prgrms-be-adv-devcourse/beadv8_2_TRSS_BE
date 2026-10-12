package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.boundedContext.member.in.dto.SellerWithdrawalRequest;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자(SELLER) 전용 내 판매자 관리. /api/v1/seller/** 는 SecurityConfig에서 SELLER만 들어온다
@Tag(name = "판매자 관리", description = "내 판매자 정보 수정·철회 요청 (SELLER)")
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class ApiV1SellerMeController {
    private final MemberSellerFacade memberSellerFacade;

    @PatchMapping("/me")
    @Operation(summary = "판매자 정보 수정",
            description = "상호·연락처·정산 계좌 중 보낸 값만 바꾼다. 정산 계좌는 은행·계좌번호·예금주를 함께 보낸다. 판매 중(APPROVED)일 때만 가능")
    public RsData<MySellerResponse> update(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody SellerUpdateRequest request
    ) {
        return RsData.of("200-1", "판매자 정보를 수정했습니다.", memberSellerFacade.updateSeller(authMember.memberId(), request));
    }

    @PostMapping("/me/withdrawal")
    @Operation(summary = "판매자 철회 요청",
            description = "판매 중(APPROVED)일 때만 요청할 수 있고, 관리자가 승인하면 철회가 끝난다. 아직 지급되지 않은 정산 금액이 있으면 요청할 수 없다")
    public RsData<MySellerResponse> requestWithdrawal(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody SellerWithdrawalRequest request
    ) {
        return RsData.of("200-1", "판매자 철회를 요청했습니다.",
                memberSellerFacade.requestWithdrawal(authMember.memberId(), request.reason()));
    }
}
