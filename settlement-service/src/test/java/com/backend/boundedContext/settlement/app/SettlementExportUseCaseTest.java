package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.in.dto.SettlementExportFile;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementExcelWriter;
import com.backend.boundedContext.settlement.out.SettlementExportItemRow;
import com.backend.boundedContext.settlement.out.SettlementExportRow;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지급 완료 정산서 엑셀 다운로드를 DB 없이 검증한다(Repository·Port는 JDK 동적 프록시, 트랜잭션은 ResourcelessTransactionManager)
 * 만든 임시 .xlsx 파일을 다시 읽어 시트·열·합계와 판매자 범위를 확인한다
 */
class SettlementExportUseCaseTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 9, 5, 12, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 10, 0, 5);

    private final List<Object[]> exportQueries = new ArrayList<>();
    private final List<Collection<?>> businessNameQueries = new ArrayList<>();
    private final List<SettlementExportFile> files = new ArrayList<>();
    private Optional<SellerDto> seller = Optional.of(new SellerDto(7L, 2L, "팔레트상점", SellerStatus.APPROVED));
    private List<SettlementExportRow> statements = List.of();
    private List<SettlementExportItemRow> items = List.of();
    private long itemCount;

    @AfterEach
    void deleteFiles() throws Exception {
        for (SettlementExportFile file : files) Files.deleteIfExists(file.file());
    }

    @Test
    @DisplayName("시작월이 끝월보다 늦거나 12개월을 넘으면 400이고 조회하지 않는다")
    void invalidRange() {
        SettlementExportUseCase useCase = useCase(policy(100, 200_000));
        DomainException reversed = assertThrows(DomainException.class, () ->
                useCase.exportForAdmin(1L, YearMonth.of(2026, 10), YearMonth.of(2026, 9), null));
        DomainException tooLong = assertThrows(DomainException.class, () ->
                useCase.exportForAdmin(1L, YearMonth.of(2025, 9), YearMonth.of(2026, 9), null));

        assertEquals("400-SETTLEMENT_MONTH_RANGE_INVALID", reversed.getResultCode());
        assertEquals("400-SETTLEMENT_MONTH_RANGE_INVALID", tooLong.getResultCode());
        assertTrue(exportQueries.isEmpty());
    }

    @Test
    @DisplayName("판매자는 본인 판매자 ID로만 조회하고, 시트 2개와 합계를 쓴다 (월별 개별 = from과 to가 같은 달)")
    void sellerExport() throws Exception {
        statements = List.of(paidRow(31L, 7L));
        items = itemsOf(31L);

        SettlementExportFile file = export(useCase(policy(100, 200_000))
                .exportForSeller(2L, YearMonth.of(2026, 9), YearMonth.of(2026, 9)));

        assertEquals("palette-settlement-2026-09.xlsx", file.fileName());
        assertEquals(7L, exportQueries.get(0)[2]);   // sellerId 조건
        try (XSSFWorkbook read = read(file)) {
            Sheet statementSheet = read.getSheet("정산서");
            assertEquals(7, statementSheet.getRow(0).getLastCellNum());
            assertEquals(27_000, (long) statementSheet.getRow(1).getCell(4).getNumericCellValue());   // 지급액
            assertEquals(PAID_AT, statementSheet.getRow(1).getCell(6).getLocalDateTimeCellValue());
            assertEquals(27_000, (long) statementSheet.getRow(2).getCell(4).getNumericCellValue());   // 합계 지급액
            Sheet itemSheet = read.getSheet("품목 내역");
            assertEquals(3, itemSheet.getLastRowNum());                                              // 머리글 + 품목 2 + 합계
            assertEquals(30_000, (long) itemSheet.getRow(3).getCell(4).getNumericCellValue());       // 합계 판매가
        }
    }

    @Test
    @DisplayName("판매자 신청 기록이 없으면 정산서를 조회하지 않고 빈 파일을 준다(남의 정산서가 섞이지 않게)")
    void sellerWithoutSellerRecord() throws Exception {
        seller = Optional.empty();

        SettlementExportFile file = export(useCase(policy(100, 200_000))
                .exportForSeller(2L, YearMonth.of(2026, 8), YearMonth.of(2026, 10)));

        assertEquals("palette-settlements-2026-08_2026-10.xlsx", file.fileName());
        assertTrue(exportQueries.isEmpty());
        try (XSSFWorkbook read = read(file)) {
            assertEquals("합계", read.getSheet("정산서").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("관리자는 전체를 받고 판매자 ID·상호명·지급 원장 ID 열이 더 있다")
    void adminExport() throws Exception {
        statements = List.of(paidRow(31L, 7L));
        items = itemsOf(31L);

        SettlementExportFile file = export(useCase(policy(100, 200_000))
                .exportForAdmin(1L, YearMonth.of(2026, 9), YearMonth.of(2026, 9), null));

        assertNull(exportQueries.get(0)[2]);   // 판매자 조건 없음
        try (XSSFWorkbook read = read(file)) {
            Sheet statementSheet = read.getSheet("정산서");
            assertEquals(10, statementSheet.getRow(0).getLastCellNum());
            assertEquals("팔레트상점", statementSheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals(501, (long) statementSheet.getRow(1).getCell(9).getNumericCellValue());
            assertEquals(27_000, (long) statementSheet.getRow(2).getCell(6).getNumericCellValue());   // 합계 지급액
            Sheet itemSheet = read.getSheet("품목 내역");
            assertEquals(12, itemSheet.getRow(0).getLastCellNum());
            assertEquals(7, (long) itemSheet.getRow(1).getCell(2).getNumericCellValue());          // 판매자 ID 열
            assertEquals(30_000, (long) itemSheet.getRow(3).getCell(5).getNumericCellValue());     // 합계 판매가
        }
    }

    @Test
    @DisplayName("품목 수가 exportMaxRows를 넘으면 400 SETTLEMENT_EXPORT_TOO_LARGE이고 정산서를 읽거나 엑셀을 만들지 않는다")
    void tooManyRows() {
        statements = List.of(paidRow(31L, 7L));
        items = itemsOf(31L);
        itemCount = 3;

        DomainException e = assertThrows(DomainException.class, () -> useCase(policy(100, 2))
                .exportForAdmin(1L, YearMonth.of(2026, 9), YearMonth.of(2026, 9), null));

        assertEquals("400-SETTLEMENT_EXPORT_TOO_LARGE", e.getResultCode());
        assertTrue(exportQueries.isEmpty());
        assertTrue(businessNameQueries.isEmpty());
    }

    @Test
    @DisplayName("상호명은 같은 판매자를 여러 묶음에서 만나도 한 번만 조회한다")
    void businessNamesCachedAcrossChunks() throws Exception {
        statements = List.of(paidRow(31L, 7L), paidRow(32L, 7L), paidRow(33L, 8L));
        items = new ArrayList<>(itemsOf(31L));
        items.addAll(itemsOf(32L));
        items.addAll(itemsOf(33L));

        SettlementExportFile file = export(useCase(policy(1, 200_000))   // 묶음 1건씩 → 묶음 3개
                .exportForAdmin(1L, YearMonth.of(2026, 9), YearMonth.of(2026, 9), null));

        assertEquals(4, exportQueries.size());                         // 묶음 3개 + 끝 확인 1번
        assertEquals(List.of(List.of(7L), List.of(8L)), businessNameQueries);   // 판매자 7은 첫 묶음에서 한 번만
        try (XSSFWorkbook read = read(file)) {
            Sheet statementSheet = read.getSheet("정산서");
            assertEquals("팔레트상점", statementSheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals(81_000, (long) statementSheet.getRow(4).getCell(6).getNumericCellValue());   // 합계 지급액 3건
        }
    }

    private SettlementExportFile export(SettlementExportFile file) {
        files.add(file);
        assertTrue(Files.exists(file.file()));
        return file;
    }

    private static XSSFWorkbook read(SettlementExportFile file) throws Exception {
        assertEquals(Files.size(file.file()), file.size());
        try (InputStream in = Files.newInputStream(file.file())) {
            return new XSSFWorkbook(in);
        }
    }

    private static SettlementPolicy policy(int batchChunkSize, int exportMaxRows) {
        return new SettlementPolicy(10, 10, 3, 10, batchChunkSize, 50, false, 2, 12, exportMaxRows, 5);
    }

    private SettlementExportUseCase useCase(SettlementPolicy policy) {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findPaidExportRows", args -> {
                    exportQueries.add(args);
                    // (정산월, ID) 커서 다음부터 pageable 크기만큼
                    String afterMonth = (String) args[3];
                    long afterId = (Long) args[4];
                    int size = ((org.springframework.data.domain.Pageable) args[5]).getPageSize();
                    return statements.stream()
                            .sorted(Comparator.comparing(SettlementExportRow::settlementMonth)
                                    .thenComparing(SettlementExportRow::id))
                            .filter(s -> s.settlementMonth().compareTo(afterMonth) > 0
                                    || (s.settlementMonth().equals(afterMonth) && s.id() > afterId))
                            .limit(size)
                            .toList();
                }));
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "countPaidExportItems", args -> itemCount,
                "findExportItemRows", args -> items.stream()
                        .filter(item -> ((Collection<?>) args[0]).contains(item.settlementId()))
                        .toList()));
        SettlementMemberPort memberPort = fake(SettlementMemberPort.class, Map.of(
                "findSellerByMemberId", args -> seller,
                "getSellers", args -> {
                    businessNameQueries.add(List.copyOf((Collection<?>) args[0]));
                    return ((Collection<?>) args[0]).stream()
                            .map(id -> new SellerDto((Long) id, 2L, (Long) id == 7L ? "팔레트상점" : "다른상점",
                                    SellerStatus.APPROVED))
                            .toList();
                }));
        SettlementSupport support = new SettlementSupport(settlementRepository, candidateRepository,
                fake(SettlementProcessHistoryRepository.class, Map.of()), memberPort);
        return new SettlementExportUseCase(settlementRepository, candidateRepository, support,
                new SettlementExcelWriter(), policy, new ResourcelessTransactionManager());
    }

    /** 2026-09 지급 완료 정산서(판매 20,000 + 10,000, 수수료 10%) */
    private static SettlementExportRow paidRow(Long id, Long sellerId) {
        return new SettlementExportRow(id, "2026-09", sellerId, 30_000L, 3_000L, 27_000L, 2, PAID_AT, 501L);
    }

    private static List<SettlementExportItemRow> itemsOf(Long settlementId) {
        return List.of(
                new SettlementExportItemRow(settlementId, 100L, settlementId * 10 + 1, 20_000L, 10, 2_000L, 18_000L,
                        CONFIRMED_AT, "2026-09"),
                new SettlementExportItemRow(settlementId, 100L, settlementId * 10 + 2, 10_000L, 10, 1_000L, 9_000L,
                        CONFIRMED_AT, "2026-09"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Function<Object[], Object> handler = handlers.get(method.getName());
            if (handler != null) return handler.apply(args);
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> type.getSimpleName() + "Fake";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
