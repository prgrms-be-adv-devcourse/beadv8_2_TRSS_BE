package com.backend.boundedContext.product.out.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 검색 매핑을 바꾸면 매핑 버전(mappings._meta.version)도 올렸는지 확인한다.
 * 매핑이 dynamic: strict라서, 필드를 바꾸고 버전을 올리지 않으면 복구 스케줄러가 재색인하지 않고 이전 인덱스가 새 문서를 모두 거부한다.
 * 매핑을 바꿨다면 _meta.version을 올리고, 이 테스트가 알려 주는 해시를 VERSION_HASHES에 새 버전으로 추가한다.
 * 설정(settings)의 분석기도 검색 결과를 바꾸므로 함께 본다. 공백·줄바꿈은 무시하고, 키 순서를 바꾸면 해시가 바뀐다.
 */
class ProductSearchIndexMappingTest {

    /** 매핑 버전 → 그 버전의 인덱스 설정·매핑(_meta 제외) 해시 */
    private static final Map<Integer, String> VERSION_HASHES = Map.of(
            2, "5efdf3550d266d1370a7f63b0f07b201879f0959d2631e65cfdfb387fe50c8b0",   // 할인율(discountRate)
            3, "affbe8ce0a2a392172a97fc4d0d1d2c189e8d43a470f3d80d870106edd01339a",   // 행동 지표(찜·리뷰·별점·판매량·행동 점수)
            4, "a0219e58875d9baba7a13c694448e9a518e00093d9b3cad13a15d79ab834931b",   // 상품 설명(비슷한 상품)
            5, "fbfccb725f57dad18b37b032e067e1df2f4d5f7478474d898a125a99638a02f6");  // 판매자 상호명(상호명 검색)

    @Test
    @DisplayName("인덱스 설정·매핑을 바꿨다면 mappings._meta.version도 올렸다")
    void mappingVersionMatchesContent() throws Exception {
        JsonNode index;
        try (InputStream in = new ClassPathResource("elasticsearch/products-index.json").getInputStream()) {
            index = new ObjectMapper().readTree(in);
        }
        int version = index.path("mappings").path("_meta").path("version").asInt();
        ObjectNode content = (ObjectNode) index.deepCopy();
        ((ObjectNode) content.path("mappings")).remove("_meta");
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.toString().getBytes(StandardCharsets.UTF_8)));

        assertThat(VERSION_HASHES.get(version))
                .as("products-index.json의 설정·매핑이 버전 %d로 기록된 내용과 다릅니다. "
                        + "매핑을 바꿨다면 _meta.version을 올리고 VERSION_HASHES에 (새 버전, \"%s\")을 추가하세요.", version, hash)
                .isEqualTo(hash);
    }
}
