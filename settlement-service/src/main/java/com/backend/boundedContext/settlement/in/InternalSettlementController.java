package com.backend.boundedContext.settlement.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.rsData.RsData;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * 정산 내부 API. {@link com.backend.shared.settlement.out.SettlementApi} 중 다른 모듈이 동기로 부르는 기능을 HTTP로 제공함
 * 정산 모듈을 분리하면 회원 쪽이 MemberSettlementPort의 HTTP 어댑터로 이 API를 부름
 * holdSeller는 HTTP로 열지 않음. 회원이 발행하는 SellerSanctionedEvent(seller.events)를 정산 컨슈머가 받아 보류함(SettlementSellerEventListener)
 * 서비스끼리만 부르는 API라 /internal 아래에 두고 Swagger에서 숨김. 응답·실패 형식은 다른 내부 API와 같음
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/settlements")
@RequiredArgsConstructor
public class InternalSettlementController {

    private final SettlementFacade settlementFacade;

    // SettlementApi.getUnsettledAmount. 정산 후보가 없는 판매자도 200 + 0
    @GetMapping("/sellers/{sellerId}/unsettled-amount")
    public RsData<Long> getUnsettledAmount(@PathVariable Long sellerId) {
        return RsData.of("200-1", "미정산 금액을 조회했습니다.", settlementFacade.getUnsettledAmount(sellerId));
    }
}
