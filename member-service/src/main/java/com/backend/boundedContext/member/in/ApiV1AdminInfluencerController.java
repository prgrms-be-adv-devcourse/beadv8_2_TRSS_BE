package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminInfluencerListDto;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.in.dto.InfluencerReasonRequest;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 인플루언서", description = "인플루언서 신청 목록·상세, 승인·반려·자격 해제")
@RestController
@RequestMapping("/api/v1/admin/influencers")
@RequiredArgsConstructor
public class ApiV1AdminInfluencerController {
    private final MemberInfluencerFacade memberInfluencerFacade;

    @Operation(summary = "인플루언서 목록 조회", description = "심사 대기(PENDING)를 먼저, 그다음 최근 신청순으로 고정(sort 값은 무시). status를 비우면 전체. counts는 status 필터와 관계없이 전체 기준 탭별 건수")
    @GetMapping
    public RsData<AdminInfluencerListDto> getInfluencers(
            @RequestParam(required = false) InfluencerStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "인플루언서 목록을 조회했습니다.", memberInfluencerFacade.getInfluencersForAdmin(status, pageable));
    }

    @Operation(summary = "인플루언서 상세 조회", description = "신청 정보와 상태 이력(오래된 순)을 반환합니다.")
    @GetMapping("/{influencerId}")
    public RsData<AdminInfluencerDetailDto> getInfluencerDetail(@PathVariable Long influencerId) {
        return RsData.of("200-1", "인플루언서 상세를 조회했습니다.", memberInfluencerFacade.getInfluencerDetail(influencerId));
    }

    @Operation(summary = "인플루언서 승인", description = "심사 대기(PENDING)만 승인할 수 있습니다. 바뀐 상세를 반환합니다.")
    @PostMapping("/{influencerId}/approve")
    public RsData<AdminInfluencerDetailDto> approve(@PathVariable Long influencerId) {
        return RsData.of("200-1", "인플루언서를 승인했습니다.", memberInfluencerFacade.approveInfluencer(influencerId));
    }

    @Operation(summary = "인플루언서 반려", description = "심사 대기(PENDING)만 반려할 수 있고, 사유는 필수입니다. 반려된 회원은 다시 신청할 수 있습니다.")
    @PostMapping("/{influencerId}/reject")
    public RsData<AdminInfluencerDetailDto> reject(
            @PathVariable Long influencerId,
            @Valid @RequestBody InfluencerReasonRequest request
    ) {
        return RsData.of("200-1", "인플루언서 신청을 반려했습니다.", memberInfluencerFacade.rejectInfluencer(influencerId, request.reason()));
    }

    @Operation(summary = "인플루언서 자격 해제", description = "승인된(APPROVED) 인플루언서만 해제할 수 있고, 사유는 필수입니다. 다시 신청할 수 없고, 협약은 자동으로 종료됩니다.")
    @PostMapping("/{influencerId}/revoke")
    public RsData<AdminInfluencerDetailDto> revoke(
            @PathVariable Long influencerId,
            @Valid @RequestBody InfluencerReasonRequest request
    ) {
        return RsData.of("200-1", "인플루언서 자격을 해제했습니다.", memberInfluencerFacade.revokeInfluencer(influencerId, request.reason()));
    }
}
