package com.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiApplicationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("앱이 뜨면 Flyway V1이 ai 스키마에 outbox_event·processed_event를 만든다")
    void migratesBaselineIntoAiSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'ai'",
                String.class);

        assertThat(tables).contains("outbox_event", "processed_event", "flyway_schema_history");
    }

    @Test
    @DisplayName("pgvector 확장이 ai 스키마에 있어 ai_user가 vector 타입을 쓸 수 있다")
    void vectorExtensionIsInAiSchema() {
        String schema = jdbcTemplate.queryForObject(
                "select n.nspname from pg_extension e join pg_namespace n on n.oid = e.extnamespace where e.extname = 'vector'",
                String.class);
        Double distance = jdbcTemplate.queryForObject("select '[1,0]'::vector <=> '[0,1]'::vector", Double.class);

        assertThat(schema).isEqualTo("ai");
        assertThat(distance).isEqualTo(1.0);
    }

    @Test
    @DisplayName("V6 초기 FAQ는 노출 중·임베딩 전이고, 해시는 질문 + 줄바꿈 + 답변의 SHA-256 hex다")
    void seedsFaqWithSourceHash() throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select question, answer, embedding_source_hash, visible, embedding is null as pending from faq");

        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            String source = row.get("question") + "\n" + row.get("answer");
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            assertThat(row.get("embedding_source_hash")).isEqualTo(hash);
            assertThat(row.get("visible")).isEqualTo(true);
            assertThat(row.get("pending")).isEqualTo(true);
        }
    }
}
