package com.backend.boundedContext.settlement.in.controller;

import com.backend.boundedContext.settlement.in.dto.SettlementExportFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * 정산서 엑셀 파일을 내려주는 응답
 * 파일은 RsData로 감싸지 않고 본문 그대로 보낸다. 오류는 다른 API처럼 GlobalExceptionHandler가 JSON(RsData)으로 보낸다
 * 파일 이름은 filename*(UTF-8)로도 넣어 한글·특수문자가 깨지지 않게 한다
 * 임시 파일을 DELETE_ON_CLOSE로 열어 보내므로, 응답을 다 보내거나 클라이언트가 끊겨 스트림이 닫히면 파일이 지워진다
 */
final class SettlementExportResponses {

    private SettlementExportResponses() {
    }

    static ResponseEntity<Resource> of(SettlementExportFile file) {
        InputStream content;
        try {
            content = Files.newInputStream(file.file(), StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException e) {
            deleteQuietly(file);
            throw new UncheckedIOException(e);
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(SettlementExportFile.CONTENT_TYPE))
                .contentLength(file.size())
                .body(new InputStreamResource(content));
    }

    private static void deleteQuietly(SettlementExportFile file) {
        try {
            Files.deleteIfExists(file.file());
        } catch (IOException ignored) {
            // 임시 폴더 파일이라 지우지 못해도 응답에는 영향이 없다
        }
    }
}
