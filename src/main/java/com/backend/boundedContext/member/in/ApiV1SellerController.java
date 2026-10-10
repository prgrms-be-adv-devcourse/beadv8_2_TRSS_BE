package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerApplyResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sellers")
@RequiredArgsConstructor
@Tag(name = "판매자", description = "판매자 입점 신청·조회")
public class ApiV1SellerController {
    private final MemberFacade memberFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "판매자 입점 신청")
    public RsData<SellerApplyResponse> apply(
            // TODO: 로그인(JWT) 기능이 완성되면 로그인한 회원 ID를 꺼내도록 교체. 지금은 테스트용 임시 헤더
            @Parameter(description = "신청 회원 ID (임시)", example = "3")
            @RequestHeader("X-Member-Id") Long memberId,
            @Valid @RequestBody SellerApplyRequest request
    ) {
        return RsData.of("201-1", "판매자 입점 신청이 접수되었습니다.", memberFacade.applySeller(memberId, request));
    }
}
