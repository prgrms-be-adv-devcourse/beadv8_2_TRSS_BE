package com.backend.boundedContext.payment.in;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.backend.boundedContext.payment.app.PaymentFacade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 컨텍스트 개발용 초기 데이터 (@Order(3), 회원 시드 이후·정산 시드 이전)
 * 정산 시드(SettlementDataInit)의 더미 주문 -1·-2에 맞춰 구매자 3번의 결제 완료 건과 HELD 홀딩을 만든다.
 * 이 홀딩이 있어야 로컬에서 정산 지급(PaymentApi.transfer)까지 실행해 볼 수 있다.
 * 구매자 3번이 시드 구매자일 때만 의미가 있으므로, 회원 시드와 같이 MEMBER_SEED_PASSWORD가 있을 때만 실행한다
 * (없으면 운영 환경이거나 회원 3번이 일반 가입자일 수 있음)
 * 구매자 지갑이 없으면 아무것도 하지 않고, 홀딩이 이미 있는 주문은 건너뛴다
 * TODO 주문·결제 흐름이 실제로 홀딩을 만들게 되면 정산 시드와 함께 지운다
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentDataInit {

    private static final Long SEED_BUYER_MEMBER_ID = 3L;

    /** 주문 ID → 결제 금액. 정산 시드의 주문별 판매 금액 합과 같다 (-1: 10,000 + 15,000, -2: 9,999) */
    private static final Map<Long, Long> SEED_HOLDINGS = new LinkedHashMap<>();

    static {
        SEED_HOLDINGS.put(-1L, 25_000L);
        SEED_HOLDINGS.put(-2L, 9_999L);
    }

    private final PaymentFacade paymentFacade;

    @Value("${custom.member.seedPassword}")
    private String seedPassword;

    @Bean
    @Order(3)
    public ApplicationRunner paymentDataInitApplicationRunner() {
        return args -> {
            if (seedPassword == null || seedPassword.isBlank()) {
                log.info("시드 회원 비밀번호(MEMBER_SEED_PASSWORD)가 없어 홀딩 시드를 건너뜀");
                return;
            }
            paymentFacade.createSeedHoldings(SEED_BUYER_MEMBER_ID, SEED_HOLDINGS);
        };
    }
}
