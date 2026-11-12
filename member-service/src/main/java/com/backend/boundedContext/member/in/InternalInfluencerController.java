package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.global.rsData.RsData;
import com.backend.shared.member.dto.InfluencerDto;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 인플루언서 내부 API. market 쿠폰(협약)이 인플루언서를 확인할 때 부른다.
 * 서비스끼리만 부르는 API라 /internal 아래에 두고 Swagger에서 숨긴다. 응답·실패 형식은 InternalSellerController와 같다
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/influencers")
@RequiredArgsConstructor
public class InternalInfluencerController {

    private final MemberInfluencerFacade memberInfluencerFacade;

    // 상태와 상관없이 조회. 없으면 404
    @GetMapping("/{influencerId}")
    public RsData<InfluencerDto> getInfluencer(@PathVariable Long influencerId) {
        return RsData.of("200-1", "인플루언서를 조회했습니다.", memberInfluencerFacade.getInternalInfluencer(influencerId));
    }

    // 상태와 상관없이 여러 명 조회. 없는 ID는 결과에서 빠진다
    @GetMapping
    public RsData<List<InfluencerDto>> getInfluencers(@RequestParam(defaultValue = "") List<Long> ids) {
        return RsData.of("200-1", "인플루언서 목록을 조회했습니다.", memberInfluencerFacade.getInternalInfluencers(ids));
    }

    // 승인된 인플루언서만. 아니면 403 INFLUENCER_NOT_APPROVED
    @GetMapping("/by-member/{memberId}/approved")
    public RsData<InfluencerDto> getApprovedInfluencerByMemberId(@PathVariable Long memberId) {
        return RsData.of("200-1", "승인된 인플루언서를 조회했습니다.", memberInfluencerFacade.getApprovedInfluencerByMemberId(memberId));
    }
}
