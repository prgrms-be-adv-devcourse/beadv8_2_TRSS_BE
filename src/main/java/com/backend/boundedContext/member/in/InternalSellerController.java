package com.backend.boundedContext.member.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.global.rsData.RsData;
import com.backend.shared.member.dto.SellerDto;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * 판매자 내부 API. {@link com.backend.shared.member.out.MemberApi}의 판매자 조회 기능을 HTTP로 제공함
 * 회원 모듈을 분리하면 주문·상품·정산 쪽이 MemberApi의 HTTP 어댑터로 이 API를 부름
 * 서비스끼리만 부르는 API라 /internal 아래에 두고 Swagger에서 숨김. 응답·실패 형식은 InternalMemberController와 같음
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/sellers")
@RequiredArgsConstructor
public class InternalSellerController {

    private final MemberSellerFacade memberSellerFacade;

    // MemberApi.getSeller. 없으면 404
    @GetMapping("/{sellerId}")
    public RsData<SellerDto> getSeller(@PathVariable Long sellerId) {
        return RsData.of("200-1", "판매자를 조회했습니다.", memberSellerFacade.getSeller(sellerId));
    }

    // MemberApi.getSellers. 없는 ID는 결과에서 빠짐
    @GetMapping
    public RsData<List<SellerDto>> getSellers(@RequestParam(defaultValue = "") List<Long> ids) {
        return RsData.of("200-1", "판매자 목록을 조회했습니다.", memberSellerFacade.getSellers(ids));
    }

    // MemberApi.findSellerByMemberId. 신청한 적이 없으면 data null
    @GetMapping("/by-member/{memberId}")
    public RsData<SellerDto> findSellerByMemberId(@PathVariable Long memberId) {
        return RsData.of("200-1", "회원의 판매자 정보를 조회했습니다.", memberSellerFacade.findSellerByMemberId(memberId).orElse(null));
    }

    // MemberApi.getApprovedSellerByMemberId. 판매 기능을 쓸 수 없는 상태면 403
    @GetMapping("/by-member/{memberId}/approved")
    public RsData<SellerDto> getApprovedSellerByMemberId(@PathVariable Long memberId) {
        return RsData.of("200-1", "승인된 판매자를 조회했습니다.", memberSellerFacade.getApprovedSellerByMemberId(memberId));
    }
}
