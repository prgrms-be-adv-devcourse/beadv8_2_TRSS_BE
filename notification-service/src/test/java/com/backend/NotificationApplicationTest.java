package com.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationApplicationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("앱이 뜨면 Flyway V1이 notification 스키마에 outbox_event·processed_event를 만든다")
    void migratesBaselineIntoNotificationSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'notification'",
                String.class);

        assertThat(tables).contains("outbox_event", "processed_event", "flyway_schema_history");
    }
}
