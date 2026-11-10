package com.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * delivery-service: 컨텍스트가 뜨고(엔티티 ↔ 스키마 validate 포함) Flyway V1·V2가 delivery 스키마에 적용된다
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class DeliveryApplicationContextTest {

    private static final String SCHEMA = "delivery";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway V1·V2가 delivery 스키마에 적용되어 있다")
    void flywayApplied() {
        List<String> versions = jdbcTemplate.queryForList(
                "select version from delivery.flyway_schema_history where success order by installed_rank",
                String.class);

        assertThat(versions).contains("1", "2");
    }

    @Test
    @DisplayName("outbox_event·processed_event 테이블이 delivery 스키마에 있다")
    void outboxTablesInDeliverySchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = ?",
                String.class, SCHEMA);

        assertThat(tables).contains("outbox_event", "processed_event");
    }

    @Test
    @DisplayName("Flyway V2로 delivery·delivery_item 테이블이 delivery 스키마에 있다")
    void deliveryTablesInDeliverySchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = ?",
                String.class, SCHEMA);

        assertThat(tables).contains("delivery", "delivery_item");
    }
}
