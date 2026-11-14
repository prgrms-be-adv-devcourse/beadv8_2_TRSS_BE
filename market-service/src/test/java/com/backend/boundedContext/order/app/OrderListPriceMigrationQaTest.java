package com.backend.boundedContext.order.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [QA] TRSS-222 V14 마이그레이션: 적용 결과(컬럼 타입·null 여부, Flyway 이력)와
 * 기존 행이 있는 테이블에 V14 본문을 그대로 실행했을 때 list_unit_price = unit_price, discount_rate = null로 채워지고 not null이 걸리는지.
 * 기존 행 시험은 같은 모양의 임시 테이블에 V14 본문(테이블 이름만 바꿈)을 실행하고 트랜잭션을 롤백한다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderListPriceMigrationQaTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("V14 적용 후: list_unit_price bigint not null, discount_rate integer null, Flyway 이력 14 성공")
    void appliedSchema() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                select column_name, data_type, is_nullable from information_schema.columns
                where table_schema = current_schema() and table_name = 'market_order_item'
                  and column_name in ('list_unit_price', 'discount_rate') order by column_name""");

        assertThat(columns).extracting(c -> c.get("column_name"), c -> c.get("data_type"), c -> c.get("is_nullable"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("discount_rate", "integer", "YES"),
                        org.assertj.core.groups.Tuple.tuple("list_unit_price", "bigint", "NO"));
        assertThat(jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '14'", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("기존 행이 있는 테이블에 V14 본문 실행: 정가 = 단가, 할인율 null로 채우고 not null이 걸린다 (롤백)")
    void backfillOnExistingRows() throws Exception {
        String v14 = new ClassPathResource("db/migration/V14__add_list_price_to_order_item.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("market_order_item", "qa_v14_order_item");

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("create temp table qa_v14_order_item (id bigint primary key, unit_price bigint not null) "
                    + "on commit drop");
            jdbcTemplate.update("insert into qa_v14_order_item (id, unit_price) values (1, 10000), (2, 5000)");

            jdbcTemplate.execute(v14);

            assertThat(jdbcTemplate.queryForList(
                    "select unit_price, list_unit_price, discount_rate from qa_v14_order_item order by id"))
                    .extracting(r -> r.get("unit_price"), r -> r.get("list_unit_price"), r -> r.get("discount_rate"))
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(10000L, 10000L, null),
                            org.assertj.core.groups.Tuple.tuple(5000L, 5000L, null));
            assertThat(jdbcTemplate.queryForObject("""
                    select is_nullable from information_schema.columns
                    where table_name = 'qa_v14_order_item' and column_name = 'list_unit_price'""", String.class))
                    .isEqualTo("NO");
            status.setRollbackOnly();
        });
    }
}
