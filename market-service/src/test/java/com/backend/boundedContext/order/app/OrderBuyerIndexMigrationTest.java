package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V20 마이그레이션: 구매자 주문 목록용 idx_market_order_buyer (buyer_id, created_at)가 만들어지는지,
 * 이미 인덱스가 있는 환경에서 다시 실행해도 실패하지 않는지 (TRSS-307)
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderBuyerIndexMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V20 적용 후 market_order에 idx_market_order_buyer (buyer_id, created_at)가 있고 Flyway 이력 20이 성공")
    void buyerIndexExists() {
        String indexDef = jdbcTemplate.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = current_schema() and tablename = 'market_order' and indexname = 'idx_market_order_buyer'""",
                String.class);

        assertThat(indexDef).endsWith("USING btree (buyer_id, created_at)");
        // V20이 마지막 마이그레이션인지는 보지 않는다. 뒤에 마이그레이션이 추가되면 깨지기 때문이다
        assertThat(jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '20'", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("인덱스가 이미 있어도 V20 본문을 다시 실행하면 실패하지 않는다 (if not exists)")
    void rerunIsNoOp() throws Exception {
        String v20 = new ClassPathResource("db/migration/V20__add_market_order_buyer_index.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        jdbcTemplate.execute(v20);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema() and indexname = 'idx_market_order_buyer'""", Integer.class))
                .isEqualTo(1);
    }
}
