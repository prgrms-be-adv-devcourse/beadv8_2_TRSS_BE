package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인플루언서", description = "인플루언서 신청·조회·프로필")
@RestController
@RequestMapping("/api/v1/influencers")
@RequiredArgsConstructor
public class ApiV1InfluencerController {
    private final MemberInfluencerFacade memberInfluencerFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "인플루언서 신청", description = "반려된 뒤에는 같은 인플루언서로 다시 신청한다")
    public RsData<MyInfluencerResponse> apply(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody InfluencerApplyRequest request
    ) {
        return RsData.of("201-1", "인플루언서 신청이 접수되었습니다.",
                memberInfluencerFacade.applyInfluencer(authMember.memberId(), request));
    }

    @GetMapping("/me")
    @Operation(summary = "내 인플루언서 신청 정보", description = "반려됐으면 그 사유와 날짜도 보여 준다. 신청한 적이 없으면 404")
    public RsData<MyInfluencerResponse> getMyInfluencer(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "내 인플루언서 정보를 조회했습니다.", memberInfluencerFacade.getMyInfluencer(authMember.memberId()));
    }

    @GetMapping("/{influencerId}")
    @Operation(summary = "인플루언서 프로필", description = "승인된 인플루언서만. 아니면 404")
    public RsData<InfluencerProfileResponse> getProfile(@PathVariable Long influencerId) {
        return RsData.of("200-1", "인플루언서 프로필을 조회했습니다.", memberInfluencerFacade.getInfluencerProfile(influencerId));
    }
}
