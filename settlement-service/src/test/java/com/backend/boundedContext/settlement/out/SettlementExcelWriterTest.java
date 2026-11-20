package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.out.SettlementExcelWriter.Column;
import com.backend.boundedContext.settlement.out.SettlementExcelWriter.ColumnType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엑셀 파일 쓰기: 임시 파일에 쓴 .xlsx를 다시 읽어 시트·머리글·값·합계·열 너비가 맞는지 본다
 */
class SettlementExcelWriterTest {

    @Test
    @DisplayName("시트 이름·머리글·값 형식·합계 행이 그대로 들어간다")
    void writeAndReadBack() throws Exception {
        Path file = Files.createTempFile("settlement-export-test-", ".xlsx");
        try (SettlementExcelWriter.Workbook workbook = new SettlementExcelWriter().open()) {
            int sheet = workbook.addSheet("정산서", List.of(
                    new Column("정산서 ID", ColumnType.ID), new Column("정산월", ColumnType.TEXT),
                    new Column("지급액", ColumnType.AMOUNT), new Column("지급 일시", ColumnType.DATETIME),
                    new Column("이월 여부", ColumnType.BOOLEAN)));
            workbook.addRow(sheet, Arrays.asList(31L, "2026-09", 18_000L, LocalDateTime.of(2026, 10, 10, 0, 5), true));
            workbook.addRow(sheet, Arrays.asList(32L, "2026-09", 9_000L, null, false));
            workbook.addTotalRow(sheet, "합계", Map.of(2, 27_000L));
            workbook.writeTo(file);
        }

        try (InputStream in = Files.newInputStream(file); XSSFWorkbook read = new XSSFWorkbook(in)) {
            Sheet sheet = read.getSheet("정산서");
            assertEquals("정산서 ID", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(31, (long) sheet.getRow(1).getCell(0).getNumericCellValue());
            assertEquals("2026-09", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(18_000, (long) sheet.getRow(1).getCell(2).getNumericCellValue());
            assertEquals(LocalDateTime.of(2026, 10, 10, 0, 5), sheet.getRow(1).getCell(3).getLocalDateTimeCellValue());
            assertEquals("예", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("아니오", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("합계", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals(27_000, (long) sheet.getRow(3).getCell(2).getNumericCellValue());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("열 너비는 내용을 재지 않고 ColumnType별 고정값(글자 수 × 256)으로 들어간다")
    void fixedColumnWidths() throws Exception {
        Path file = Files.createTempFile("settlement-export-test-", ".xlsx");
        try {
            try (SettlementExcelWriter.Workbook workbook = new SettlementExcelWriter().open()) {
                int sheet = workbook.addSheet("품목 내역", List.of(
                        new Column("정산서 ID", ColumnType.ID), new Column("판매가", ColumnType.AMOUNT),
                        new Column("수수료율", ColumnType.PERCENT), new Column("구매 확정 시각", ColumnType.DATETIME),
                        new Column("이월 여부", ColumnType.BOOLEAN), new Column("정산월", ColumnType.TEXT),
                        new Column("아주 긴 머리글 텍스트 열", ColumnType.TEXT)));
                workbook.addRow(sheet, Arrays.asList(1L, 123_456_789_012L, 10,
                        LocalDateTime.of(2026, 9, 5, 12, 0), true, "2026-09", "x".repeat(100)));
                workbook.writeTo(file);
            }

            try (InputStream in = Files.newInputStream(file); XSSFWorkbook read = new XSSFWorkbook(in)) {
                Sheet sheet = read.getSheet("품목 내역");
                assertEquals(12 * 256, sheet.getColumnWidth(0));   // ID
                assertEquals(14 * 256, sheet.getColumnWidth(1));   // AMOUNT
                assertEquals(9 * 256, sheet.getColumnWidth(2));    // PERCENT
                assertEquals(20 * 256, sheet.getColumnWidth(3));   // DATETIME
                assertEquals(9 * 256, sheet.getColumnWidth(4));    // BOOLEAN
                assertEquals(14 * 256, sheet.getColumnWidth(5));   // TEXT 짧은 머리글: 최소 14
                assertEquals(("아주 긴 머리글 텍스트 열".length() * 2 + 2) * 256, sheet.getColumnWidth(6));   // TEXT 긴 머리글: 글자 수 × 2 + 2 (내용 길이와 무관)
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("합계 행은 합계 앞 열들을 합쳐 라벨을 쓰고, 자동 필터는 데이터 행까지만, 지정 너비와 굵은 열이 들어간다")
    void totalRowLayoutFilterAndColumnOptions() throws Exception {
        Path file = Files.createTempFile("settlement-export-test-", ".xlsx");
        try {
            try (SettlementExcelWriter.Workbook workbook = new SettlementExcelWriter().open()) {
                int sheet = workbook.addSheet("정산서", List.of(
                        new Column("정산월", ColumnType.TEXT), new Column("정산서 ID", ColumnType.ID),
                        Column.wide("상호명", ColumnType.TEXT, 22),
                        new Column("판매 대금", ColumnType.AMOUNT), Column.strong("지급액", ColumnType.AMOUNT)));
                workbook.addRow(sheet, Arrays.asList("2026-09", 31L, "팔레트상점", 30_000L, 27_000L));
                workbook.addRow(sheet, Arrays.asList("2026-09", 32L, "블라썸 아뜰리에", 10_000L, 9_000L));
                workbook.addTotalRow(sheet, "합계 (2건)", Map.of(3, 40_000L, 4, 36_000L));
                workbook.writeTo(file);
            }
            try (InputStream in = Files.newInputStream(file); XSSFWorkbook read = new XSSFWorkbook(in)) {
                Sheet sheet = read.getSheet("정산서");
                // 합계 라벨: 0~2열을 합친 칸, 오른쪽 정렬, 위 테두리
                assertEquals("합계 (2건)", sheet.getRow(3).getCell(0).getStringCellValue());
                assertEquals(1, sheet.getNumMergedRegions());
                assertEquals("A4:C4", sheet.getMergedRegion(0).formatAsString());
                assertEquals(HorizontalAlignment.RIGHT, sheet.getRow(3).getCell(0).getCellStyle().getAlignment());
                assertEquals(BorderStyle.THIN, sheet.getRow(3).getCell(4).getCellStyle().getBorderTop());
                assertEquals(36_000, (long) sheet.getRow(3).getCell(4).getNumericCellValue());
                // 자동 필터는 머리글~마지막 데이터 행(합계 행 제외)
                assertEquals("A1:E3", ((XSSFSheet) sheet).getCTWorksheet().getAutoFilter().getRef());
                // 지정 너비, 굵은 열
                assertEquals(22 * 256, sheet.getColumnWidth(2));
                assertTrue(read.getFontAt(sheet.getRow(1).getCell(4).getCellStyle().getFontIndex()).getBold());
                assertFalse(read.getFontAt(sheet.getRow(1).getCell(3).getCellStyle().getFontIndex()).getBold());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
