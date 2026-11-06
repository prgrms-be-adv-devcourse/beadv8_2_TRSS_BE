package com.backend.boundedContext.member.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.app.MemberSellerFacade;

import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트 개발용 초기 데이터. 1번 관리자, 2번 판매자, 3번 구매자와 각자의 지갑을 만들고,
 * 2번 판매자 회원은 승인된 판매자(APPROVED)로 만듦. 그다음 물류 담당자 계정을 만듦 (지갑 없음)
 * MEMBER_SEED_PASSWORD가 없으면 아무것도 하지 않음. 회원·판매자·물류 담당자가 이미 있으면 그 단계는 건너뜀
 */
@Configuration
@RequiredArgsConstructor
public class MemberDataInit {

    private static final String SEED_SELLER_EMAIL = "seller@palette.test";
    private static final String SEED_LOGISTICS_EMAIL = "logistics@palette.test";

    private final MemberFacade memberFacade;
    private final MemberSellerFacade memberSellerFacade;

    @Value("${custom.member.seedPassword}")
    private String seedPassword;

    @Bean
    @Order(1)
    public ApplicationRunner memberDataInitApplicationRunner() {
        return args -> {
            memberFacade.createSeedMembers(seedPassword);
            if (seedPassword != null && !seedPassword.isBlank()) {
                memberSellerFacade.createSeedSeller(SEED_SELLER_EMAIL);
            }
            memberFacade.createSeedLogistics(SEED_LOGISTICS_EMAIL, seedPassword);
        };
    }
}
