package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "판매자 - 인플루언서", description = "판매자가 협약 상대 인플루언서를 찾는다")
@RestController
@RequestMapping("/api/v1/seller/influencers")
@RequiredArgsConstructor
public class ApiV1SellerInfluencerController {
    private final MemberInfluencerFacade memberInfluencerFacade;

    @GetMapping
    @Operation(summary = "협약할 인플루언서 검색", description = "판매 중인 판매자만(아니면 403). 승인된 인플루언서를 활동명 부분 일치로 찾는다. keyword를 비우면 전체. 최근 신청순")
    public RsData<PageResponse<InfluencerProfileResponse>> search(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) String keyword,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "인플루언서를 검색했습니다.",
                memberInfluencerFacade.searchInfluencers(authMember.memberId(), keyword, pageable));
    }
}
