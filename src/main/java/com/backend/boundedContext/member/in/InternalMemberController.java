package com.backend.boundedContext.member.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.in.dto.MemberEmailSearchRequest;
import com.backend.global.rsData.RsData;
import com.backend.shared.member.dto.MemberDto;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트 내부 API. {@link com.backend.shared.member.out.MemberApi}를 HTTP로 제공함
 * 회원 모듈을 분리하면 결제·주문·상품·정산 쪽이 MemberApi의 HTTP 어댑터로 이 API를 부름
 * 사용자용 API(/api/v1/**)가 아니라 서비스끼리만 부르는 API라 /internal 아래에 두고 Swagger에서 숨김
 * 응답은 다른 API와 같은 RsData 형식이고, 실패는 GlobalExceptionHandler가 같은 resultCode(예: 404-MEMBER_NOT_FOUND)로 돌려줌
 * 결과가 없을 수 있는 조회(Optional)는 200 + data null로 응답함
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/members")
@RequiredArgsConstructor
public class InternalMemberController {

    private final MemberFacade memberFacade;

    // MemberApi.getMember. 없으면 404
    @GetMapping("/{memberId}")
    public RsData<MemberDto> getMember(@PathVariable Long memberId) {
        return RsData.of("200-1", "회원을 조회했습니다.", memberFacade.getMember(memberId));
    }

    // MemberApi.checkActiveMember. 없거나 탈퇴했으면 401
    @GetMapping("/{memberId}/active")
    public RsData<Void> checkActiveMember(@PathVariable Long memberId) {
        memberFacade.checkActiveMember(memberId);
        return RsData.of("200-1", "활성 회원입니다.");
    }

    // MemberApi.getMembers. 없는 ID는 결과에서 빠짐
    @GetMapping
    public RsData<List<MemberDto>> getMembers(@RequestParam(defaultValue = "") List<Long> ids) {
        return RsData.of("200-1", "회원 목록을 조회했습니다.", memberFacade.getMembersByIds(ids));
    }

    // MemberApi.findMemberByEmail. 없으면 data null
    @PostMapping("/search-by-email")
    public RsData<MemberDto> findMemberByEmail(@RequestBody MemberEmailSearchRequest request) {
        return RsData.of("200-1", "이메일로 회원을 조회했습니다.", memberFacade.findMemberByEmail(request.email()).orElse(null));
    }
}
