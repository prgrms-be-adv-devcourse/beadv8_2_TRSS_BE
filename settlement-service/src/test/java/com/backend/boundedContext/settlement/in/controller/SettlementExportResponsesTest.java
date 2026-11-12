package com.backend.boundedContext.settlement.in.controller;

import com.backend.boundedContext.settlement.in.dto.SettlementExportFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산서 엑셀 응답: 임시 파일을 그대로 본문으로 보내고, 응답 스트림이 닫히면 임시 파일이 지워진다
 */
class SettlementExportResponsesTest {

    @Test
    @DisplayName("파일 이름(UTF-8)·Content-Type·Content-Length를 넣고, 본문 스트림을 닫으면 임시 파일이 지워진다")
    void deleteOnClose() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        Path file = Files.createTempFile("settlement-export-test-", ".xlsx");
        Files.write(file, bytes);
        try {
            ResponseEntity<Resource> response = SettlementExportResponses.of(
                    new SettlementExportFile("palette-settlement-2026-09.xlsx", file, bytes.length));

            assertEquals(SettlementExportFile.CONTENT_TYPE, response.getHeaders().getContentType().toString());
            assertEquals(bytes.length, response.getHeaders().getContentLength());
            assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                    .contains("filename*=UTF-8''palette-settlement-2026-09.xlsx"));
            try (InputStream in = response.getBody().getInputStream()) {
                assertArrayEquals(bytes, in.readAllBytes());
            }
            assertFalse(Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
