package com.backend.boundedContext.settlement.in.dto;

import java.nio.file.Path;

/**
 * 정산서 엑셀 다운로드 결과
 * 파일 내용을 메모리(byte[])에 들고 있지 않고 임시 파일로 넘긴다. 응답을 다 보내거나 클라이언트가 끊으면 지운다
 * (SettlementExportResponses가 DELETE_ON_CLOSE로 열어 보낸다)
 *
 * @param fileName 내려줄 파일 이름 (예: palette-settlements-2026-08_2026-10.xlsx)
 * @param file     .xlsx 임시 파일
 * @param size     파일 크기(바이트). Content-Length로 쓴다
 */
public record SettlementExportFile(String fileName, Path file, long size) {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
}
