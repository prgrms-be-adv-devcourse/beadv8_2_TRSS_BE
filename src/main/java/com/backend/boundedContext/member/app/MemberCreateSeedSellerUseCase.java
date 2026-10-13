package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberCreateSeedSellerUseCase {

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;

    /**
     * 개발용 시드 판매자 생성: 시드 판매자 회원(seller@palette.test)을 승인된 판매자(APPROVED)로 만든다
     * 실제 흐름과 같게 신청(PENDING) → 승인(APPROVED) 이력을 남긴다
     * 시드 회원이 없거나 이미 판매자 정보가 있으면 아무것도 하지 않는다(멱등)
     */
    public void createSeedSeller(String sellerEmail) {
        Member member = memberRepository.findByEmail(sellerEmail).orElse(null);
        if (member == null) {
            log.info("시드 판매자 회원이 없어 판매자 시드를 건너뜀: email={}", sellerEmail);
            return;
        }
        if (sellerRepository.findByMemberId(member.getId()).isPresent()) return;

        Seller seller = Seller.apply(
                member,
                "팔레트 시드 상점",
                "1000000002",
                "판매자",
                "01000000002",
                "국민은행",
                "00000000000002",
                "판매자"
        );

        sellerRepository.saveAndFlush(seller);
        sellerStatusHistoryRepository.save(SellerStatusHistory.applied(seller));
        sellerStatusHistoryRepository.save(seller.approve());

        log.info("시드 판매자 생성: sellerId={}, memberId={}", seller.getId(), member.getId());
    }
}
