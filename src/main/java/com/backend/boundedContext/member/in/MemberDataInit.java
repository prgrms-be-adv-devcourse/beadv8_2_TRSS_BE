package com.backend.boundedContext.member.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.backend.boundedContext.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트 개발용 초기 데이터. 1번 관리자, 2번 판매자, 3번 구매자와 각자의 지갑을 만듦
 * MEMBER_SEED_PASSWORD가 없거나 회원이 이미 있으면 아무것도 하지 않음
 */
@Configuration
@RequiredArgsConstructor
public class MemberDataInit {

    private final MemberFacade memberFacade;

    @Value("${custom.member.seedPassword}")
    private String seedPassword;

    @Bean
    @Order(1)
    public ApplicationRunner memberDataInitApplicationRunner() {
        return args -> memberFacade.createSeedMembers(seedPassword);
    }
}
