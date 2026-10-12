package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "판매자", description = "판매자 입점 신청·조회")
@RestController
@RequestMapping("/api/v1/sellers")
@RequiredArgsConstructor
public class ApiV1SellerController {
    private final MemberFacade memberFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "판매자 입점 신청", description = "반려·본인 철회 뒤에는 같은 판매자로 다시 신청한다")
    public RsData<SellerApplyResponse> apply(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody SellerApplyRequest request
    ) {
        return RsData.of("201-1", "판매자 입점 신청이 접수되었습니다.",
                memberFacade.applySeller(authMember.memberId(), request));
    }

    @GetMapping("/me")
    @Operation(summary = "내 판매자 정보·신청 상태 조회", description = "반려·철회됐으면 그 사유도 보여 준다. 계좌번호는 끝 4자리만")
    public RsData<MySellerResponse> getMySeller(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "내 판매자 정보를 조회했습니다.", memberFacade.getMySeller(authMember.memberId()));
    }
}
