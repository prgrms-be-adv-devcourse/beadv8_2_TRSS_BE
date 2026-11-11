package com.backend.boundedContext.settlement.out;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 정산서 엑셀(.xlsx) 파일 쓰기
 *
 * Apache POI SXSSF로 메모리에는 ROW_WINDOW(100)행만 두고 나머지는 임시 파일로 흘려 쓴다(행이 많아도 메모리를 적게 쓴다).
 * 시트·열·행을 쓰는 방법만 알고, 어떤 열을 넣을지는 UseCase가 정한다.
 * 한 시트는 엑셀 최대 행 수(1,048,576)를 넘길 수 없어 MAX_DATA_ROWS를 넘으면 TooManyRowsException을 던진다.
 * 열 너비는 열 형식(ColumnType)별 고정값으로 정한다. 자동 맞춤은 모든 셀의 글자 폭을 재서(행 수 × 열 수) 큰 파일에서 느리다.
 */
@Component
public class SettlementExcelWriter {

    private static final int ROW_WINDOW = 100;
    /** 열 너비 단위(글자 1개 = 1/256). setColumnWidth는 글자 수 × 256을 받는다 */
    private static final int CHAR_WIDTH = 256;
    /** TEXT 열의 최소 너비(글자 수). 한글 머리글은 영문보다 넓어 머리글 글자 수 × 2 + 2와 비교해 큰 값을 쓴다 */
    private static final int MIN_TEXT_WIDTH = 14;
    /** 머리글 1행 + 합계 1행을 뺀 데이터 행 수 */
    public static final int MAX_DATA_ROWS = 1_048_576 - 2;

    /** 열 값의 형식 */
    public enum ColumnType {
        /** 문자열 */
        TEXT,
        /** ID처럼 쉼표 없는 정수 */
        ID,
        /** 금액·건수처럼 천 단위 쉼표를 붙이는 정수 */
        AMOUNT,
        /** 수수료율(%) 정수 */
        PERCENT,
        /** yyyy-MM-dd HH:mm:ss */
        DATETIME,
        /** 예·아니오 */
        BOOLEAN
    }

    public record Column(String header, ColumnType type) {
    }

    public static class TooManyRowsException extends RuntimeException {
        public TooManyRowsException() {
            super("엑셀 한 시트의 최대 행 수를 넘었습니다.");
        }
    }

    public Workbook open() {
        return new Workbook();
    }

    /** 한 파일. try-with-resources로 닫아 임시 파일을 지운다 */
    public static class Workbook implements AutoCloseable {

        private final SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        private final CellStyle headerStyle;
        private final CellStyle amountStyle;
        private final CellStyle idStyle;
        private final CellStyle percentStyle;
        private final CellStyle dateTimeStyle;
        private final CellStyle totalStyle;
        private final List<SheetState> sheets = new ArrayList<>();

        private Workbook() {
            DataFormat format = workbook.createDataFormat();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);
            amountStyle = workbook.createCellStyle();
            amountStyle.setDataFormat(format.getFormat("#,##0"));
            idStyle = workbook.createCellStyle();
            idStyle.setDataFormat(format.getFormat("0"));
            percentStyle = workbook.createCellStyle();
            percentStyle.setDataFormat(format.getFormat("0\"%\""));
            dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(format.getFormat("yyyy-mm-dd hh:mm:ss"));
            totalStyle = workbook.createCellStyle();
            totalStyle.setFont(bold);
            totalStyle.setDataFormat(format.getFormat("#,##0"));
        }

        /** 시트를 만들고 머리글 행을 쓴다. @return 시트 번호(addRow에 넘긴다) */
        public int addSheet(String name, List<Column> columns) {
            SXSSFSheet sheet = workbook.createSheet(name);
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, widthOf(columns.get(i)) * CHAR_WIDTH);
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).header());
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1);
            sheets.add(new SheetState(sheet, List.copyOf(columns)));
            return sheets.size() - 1;
        }

        /** 데이터 행 1개. values는 열 순서대로, null이면 빈 칸 */
        public void addRow(int sheetIndex, List<?> values) {
            SheetState state = sheets.get(sheetIndex);
            if (state.dataRows >= MAX_DATA_ROWS) throw new TooManyRowsException();
            Row row = state.sheet.createRow(++state.dataRows);
            for (int i = 0; i < state.columns.size(); i++) {
                writeCell(row.createCell(i), state.columns.get(i).type(), values.get(i));
            }
        }

        /** 마지막 합계 행. totals는 열 번호 → 합계 */
        public void addTotalRow(int sheetIndex, String label, Map<Integer, Long> totals) {
            SheetState state = sheets.get(sheetIndex);
            Row row = state.sheet.createRow(state.dataRows + 1);
            Cell labelCell = row.createCell(0);
            labelCell.setCellValue(label);
            labelCell.setCellStyle(headerStyle);
            totals.forEach((column, total) -> {
                Cell cell = row.createCell(column);
                cell.setCellValue(total);
                cell.setCellStyle(totalStyle);
            });
        }

        /** 완성된 파일을 file에 쓴다(파일 전체를 메모리에 올리지 않는다). 파일을 만들고 지우는 일은 호출한 쪽이 한다 */
        public void writeTo(Path file) {
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                workbook.close();
            } catch (IOException ignored) {
                // 닫기 실패는 응답에 영향이 없다
            }
            workbook.dispose();   // SXSSF 임시 파일 삭제
        }

        /** 열 너비(글자 수). ID 12, AMOUNT 14, PERCENT 9, DATETIME 20, BOOLEAN 9, TEXT는 머리글 글자 수 × 2 + 2와 14 중 큰 값 */
        static int widthOf(Column column) {
            return switch (column.type()) {
                case ID -> 12;
                case AMOUNT -> 14;
                case PERCENT, BOOLEAN -> 9;
                case DATETIME -> 20;
                case TEXT -> Math.max(column.header().length() * 2 + 2, MIN_TEXT_WIDTH);
            };
        }

        private void writeCell(Cell cell, ColumnType type, Object value) {
            if (value == null) return;
            switch (type) {
                case TEXT -> cell.setCellValue(value.toString());
                case BOOLEAN -> cell.setCellValue(Boolean.TRUE.equals(value) ? "예" : "아니오");
                case DATETIME -> {
                    cell.setCellValue((LocalDateTime) value);
                    cell.setCellStyle(dateTimeStyle);
                }
                case ID, AMOUNT, PERCENT -> {
                    cell.setCellValue(((Number) value).doubleValue());
                    cell.setCellStyle(switch (type) {
                        case ID -> idStyle;
                        case PERCENT -> percentStyle;
                        default -> amountStyle;
                    });
                }
            }
        }

        private static final class SheetState {
            private final Sheet sheet;
            private final List<Column> columns;
            private int dataRows;

            private SheetState(Sheet sheet, List<Column> columns) {
                this.sheet = sheet;
                this.columns = columns;
            }
        }
    }
}
