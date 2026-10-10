package com.backend.boundedContext.member.out;

import com.backend.boundedContext.member.domain.Seller;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class SellerIndexInitializer {
    private final JdbcTemplate jdbcTemplate;

    // EntityManagerFactory는 실행 순서(테이블 생성 후)를 보장하려고 주입받는다
    public SellerIndexInitializer(JdbcTemplate jdbcTemplate, EntityManagerFactory entityManagerFactory) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void createPartialUniqueIndexes() {
        String ongoingCondition = Seller.ONGOING_STATUSES.stream()
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", ", "status IN (", ")"));

        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + Seller.UQ_ONGOING_MEMBER
                + " ON seller (member_id) WHERE " + ongoingCondition);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + Seller.UQ_ONGOING_BUSINESS_NUMBER
                + " ON seller (business_number) WHERE " + ongoingCondition);
    }
}