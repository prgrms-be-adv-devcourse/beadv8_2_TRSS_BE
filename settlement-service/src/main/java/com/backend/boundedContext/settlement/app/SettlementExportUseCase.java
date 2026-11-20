package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.in.dto.SettlementExportFile;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementExcelWriter;
import com.backend.boundedContext.settlement.out.SettlementExcelWriter.Column;
import com.backend.boundedContext.settlement.out.SettlementExcelWriter.ColumnType;
import com.backend.boundedContext.settlement.out.SettlementExportItemRow;
import com.backend.boundedContext.settlement.out.SettlementExportRow;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 지급 완료(PAID) 정산서 엑셀 다운로드 (시트 2개: ① 정산서 ② 품목 내역, 각 시트 맨 아래 "합계 (N건)" 행)
 *
 * 지급이 끝난 정산 내역을 확인하는 기능이라 PAID 정산서만 담는다(지급 예정·지연·보류는 금액이 바뀔 수 있어 넣지 않는다).
 * 판매자는 본인 정산서만, 관리자는 전체(판매자 ID로 거르기 가능)를 받는다. 월별 개별 다운로드는 from = to.
 * 기간은 최대 maxSearchMonths(12)개월이고, 쓰기 전에 품목 수를 세어 exportMaxRows를 넘으면 400으로 거절한다.
 *
 * 행이 많아도 메모리·DB 연결을 오래 잡지 않도록:
 * - 정산서를 (정산월, ID) 커서로 batchChunkSize(100)건씩 읽고, 그 정산서들의 품목을 한 번에 읽어(N+1 방지) 바로 쓴다
 * - 엔티티 대신 필요한 값만(SettlementExportRow·SettlementExportItemRow) 읽어 영속성 컨텍스트에 쌓이지 않게 한다
 * - 트랜잭션은 묶음 조회(정산서 + 품목)마다 짧은 읽기 트랜잭션으로 연다. 판매자·상호명 조회(회원 서비스 HTTP)는 트랜잭션 밖에서 한다
 * - 상호명은 다운로드 한 번 동안 판매자 ID별로 캐시하고, 처음 보는 판매자만 묶어서 조회한다
 * - 완성된 파일은 byte[]가 아니라 임시 파일로 넘긴다(만드는 도중 실패하면 여기서 지운다)
 */
@Slf4j
@Component
public class SettlementExportUseCase {

    private static final String STATEMENT_SHEET = "정산서";
    private static final String ITEM_SHEET = "품목 내역";
    private static final String TOTAL_LABEL = "합계";
    private static final String NO_CURSOR_MONTH = "";
    private static final String TEMP_FILE_PREFIX = "settlement-export-";
    private static final String TEMP_FILE_SUFFIX = ".xlsx";

    /*
     * 열 순서: 왼쪽은 "누구에게, 언제"(정산월, ID, 판매자, 지급 일시와 지급 원장), 오른쪽 끝은 "얼마"(건수와 금액).
     * 합계가 붙는 열이 오른쪽에 모이고, 합계 행 라벨은 그 앞 열들을 합친 칸에 오른쪽 정렬로 들어간다. 지급액은 굵게
     */
    private static final List<Column> SELLER_STATEMENT_COLUMNS = List.of(
            new Column("정산월", ColumnType.TEXT), new Column("정산서 ID", ColumnType.ID),
            new Column("지급 일시", ColumnType.DATETIME),
            new Column("품목 수", ColumnType.AMOUNT), new Column("판매 대금", ColumnType.AMOUNT),
            new Column("수수료", ColumnType.AMOUNT), Column.strong("지급액", ColumnType.AMOUNT));

    private static final List<Column> ADMIN_STATEMENT_COLUMNS = List.of(
            new Column("정산월", ColumnType.TEXT), new Column("정산서 ID", ColumnType.ID),
            new Column("판매자 ID", ColumnType.ID), Column.wide("상호명", ColumnType.TEXT, 22),
            new Column("지급 일시", ColumnType.DATETIME), new Column("지급 원장 ID", ColumnType.ID),
            new Column("품목 수", ColumnType.AMOUNT), new Column("판매 대금", ColumnType.AMOUNT),
            new Column("수수료", ColumnType.AMOUNT), Column.strong("지급액", ColumnType.AMOUNT));

    /* 품목 내역: 정산월 정보(정산서 정산월, 원래 정산월, 이월 여부)를 앞에 모아 "왜 이 달 정산에 들어갔는지"가 보이게 한다 */
    private static final List<Column> SELLER_ITEM_COLUMNS = List.of(
            new Column("정산서 ID", ColumnType.ID), new Column("정산서 정산월", ColumnType.TEXT),
            new Column("원래 정산월", ColumnType.TEXT), new Column("이월 여부", ColumnType.BOOLEAN),
            new Column("구매 확정 시각", ColumnType.DATETIME),
            new Column("주문 ID", ColumnType.ID), new Column("주문 항목 ID", ColumnType.ID),
            Column.wide("상품명", ColumnType.TEXT, 28), Column.wide("옵션명", ColumnType.TEXT, 18),
            new Column("수수료율", ColumnType.PERCENT),
            new Column("판매가", ColumnType.AMOUNT), new Column("상품 쿠폰 할인액", ColumnType.AMOUNT),
            new Column("수수료", ColumnType.AMOUNT), Column.strong("지급액", ColumnType.AMOUNT));

    /** 관리자 품목 내역: 주문 항목 ID 뒤에 판매자 ID, 상호명 */
    private static final List<Column> ADMIN_ITEM_COLUMNS;

    static {
        List<Column> columns = new ArrayList<>(SELLER_ITEM_COLUMNS);
        columns.add(7, new Column("판매자 ID", ColumnType.ID));
        columns.add(8, Column.wide("상호명", ColumnType.TEXT, 22));
        ADMIN_ITEM_COLUMNS = List.copyOf(columns);
    }

    private final SettlementRepository settlementRepository;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementSupport settlementSupport;
    private final SettlementExcelWriter settlementExcelWriter;
    private final SettlementPolicy settlementPolicy;
    // 묶음 조회마다 여는 짧은 읽기 트랜잭션. 호출한 쪽에 트랜잭션이 있어도 따로 열고 바로 닫는다
    private final TransactionTemplate readTransaction;

    public SettlementExportUseCase(SettlementRepository settlementRepository,
                                   SettlementCandidateRepository settlementCandidateRepository,
                                   SettlementSupport settlementSupport,
                                   SettlementExcelWriter settlementExcelWriter,
                                   SettlementPolicy settlementPolicy,
                                   PlatformTransactionManager transactionManager) {
        this.settlementRepository = settlementRepository;
        this.settlementCandidateRepository = settlementCandidateRepository;
        this.settlementSupport = settlementSupport;
        this.settlementExcelWriter = settlementExcelWriter;
        this.settlementPolicy = settlementPolicy;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    /** 판매자 본인 지급 완료 정산서. 판매자 신청 기록이 없으면 정산서를 조회하지 않고 빈 파일(머리글·합계만) */
    public SettlementExportFile exportForSeller(Long memberId, YearMonth from, YearMonth to) {
        settlementPolicy.validateMonthRange(from, to);
        Optional<Long> sellerId = settlementSupport.findSellerId(memberId);
        ExportResult result = write(from, to, sellerId.orElse(null), sellerId.isEmpty(), false);
        return new SettlementExportFile(fileName(from, to), result.file(), result.size());
    }

    /** 관리자 전체 지급 완료 정산서(sellerId로 거르기 가능). 누가·언제·어떤 조건으로 받았는지 로그로 남긴다 */
    public SettlementExportFile exportForAdmin(Long adminMemberId, YearMonth from, YearMonth to, Long sellerId) {
        settlementPolicy.validateMonthRange(from, to);
        ExportResult result = write(from, to, sellerId, false, true);
        log.info("관리자 정산서 엑셀 다운로드 adminMemberId={} from={} to={} sellerId={} statements={} items={}",
                adminMemberId, from, to, sellerId, result.statementCount(), result.itemCount());
        return new SettlementExportFile(fileName(from, to), result.file(), result.size());
    }

    private ExportResult write(YearMonth from, YearMonth to, Long sellerId, boolean empty, boolean admin) {
        if (!empty) checkRowLimit(from, to, sellerId);

        Path file = createTempFile();
        try (SettlementExcelWriter.Workbook workbook = settlementExcelWriter.open()) {
            int statementSheet = workbook.addSheet(STATEMENT_SHEET,
                    admin ? ADMIN_STATEMENT_COLUMNS : SELLER_STATEMENT_COLUMNS);
            int itemSheet = workbook.addSheet(ITEM_SHEET, admin ? ADMIN_ITEM_COLUMNS : SELLER_ITEM_COLUMNS);
            Totals statementTotals = new Totals();
            Totals itemTotals = new Totals();
            BusinessNames names = new BusinessNames();

            String afterMonth = NO_CURSOR_MONTH;
            long afterId = 0L;
            while (!empty) {
                Chunk chunk = readChunk(from, to, sellerId, afterMonth, afterId);
                if (chunk.statements().isEmpty()) break;
                if (admin) names.load(chunk.statements());   // 트랜잭션 밖에서 회원 서비스 조회

                for (SettlementExportRow s : chunk.statements()) {
                    workbook.addRow(statementSheet, admin ? adminStatementRow(s, names.get(s.sellerId()))
                            : sellerStatementRow(s));
                    String businessName = admin ? names.get(s.sellerId()) : null;
                    statementTotals.add(s.totalSalePrice(), s.totalFee(), s.payoutAmount(), s.itemCount());
                    for (SettlementExportItemRow c : chunk.itemsOf(s.id())) {
                        workbook.addRow(itemSheet, itemRow(s, c, admin, businessName));
                        itemTotals.add(c.salePrice(), c.fee(), c.payoutAmount(), 1);
                        itemTotals.addDiscount(c.productCouponDiscount());
                    }
                }
                SettlementExportRow last = chunk.statements().get(chunk.statements().size() - 1);
                afterMonth = last.settlementMonth();
                afterId = last.id();
            }

            List<Column> statementColumns = admin ? ADMIN_STATEMENT_COLUMNS : SELLER_STATEMENT_COLUMNS;
            List<Column> itemColumns = admin ? ADMIN_ITEM_COLUMNS : SELLER_ITEM_COLUMNS;
            workbook.addTotalRow(statementSheet, totalLabel(statementTotals.count),
                    statementTotals.statementColumns(indexOf(statementColumns, "판매 대금"),
                            indexOf(statementColumns, "수수료"), indexOf(statementColumns, "지급액"),
                            indexOf(statementColumns, "품목 수")));
            workbook.addTotalRow(itemSheet, totalLabel(itemTotals.count),
                    itemTotals.itemColumns(indexOf(itemColumns, "판매가"), indexOf(itemColumns, "상품 쿠폰 할인액"),
                            indexOf(itemColumns, "수수료"), indexOf(itemColumns, "지급액")));
            workbook.writeTo(file);
            return new ExportResult(file, Files.size(file), statementTotals.count, itemTotals.count);
        } catch (SettlementExcelWriter.TooManyRowsException e) {
            deleteQuietly(file);
            throw new DomainException(SettlementErrorCode.SETTLEMENT_EXPORT_TOO_LARGE, e);
        } catch (IOException | UncheckedIOException e) {
            deleteQuietly(file);
            throw new DomainException(SettlementErrorCode.SETTLEMENT_EXPORT_FAILED, e);
        } catch (RuntimeException e) {
            deleteQuietly(file);   // 상호명 조회 실패 등은 그대로 던진다
            throw e;
        }
    }

    /** 쓰기 전에 품목 수를 세어 exportMaxRows를 넘으면 파일을 만들지 않고 400 */
    private void checkRowLimit(YearMonth from, YearMonth to, Long sellerId) {
        Long items = readTransaction.execute(status ->
                settlementCandidateRepository.countPaidExportItems(from.toString(), to.toString(), sellerId));
        if (items != null && items > settlementPolicy.getExportMaxRows()) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_EXPORT_TOO_LARGE);
        }
    }

    /** 묶음 하나(정산서 batchChunkSize건 + 그 품목)를 짧은 읽기 트랜잭션 하나에서 읽는다 */
    private Chunk readChunk(YearMonth from, YearMonth to, Long sellerId, String afterMonth, long afterId) {
        return readTransaction.execute(status -> {
            List<SettlementExportRow> statements = settlementRepository.findPaidExportRows(from.toString(),
                    to.toString(), sellerId, afterMonth, afterId,
                    PageRequest.of(0, settlementPolicy.getBatchChunkSize()));
            if (statements.isEmpty()) return new Chunk(List.of(), Map.of());
            Map<Long, List<SettlementExportItemRow>> items = settlementCandidateRepository
                    .findExportItemRows(statements.stream().map(SettlementExportRow::id).toList()).stream()
                    .collect(Collectors.groupingBy(SettlementExportItemRow::settlementId));
            return new Chunk(statements, items);
        });
    }

    private static Path createTempFile() {
        try {
            return Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
        } catch (IOException e) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_EXPORT_FAILED, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 임시 폴더 파일이라 지우지 못해도 응답에는 영향이 없다
        }
    }

    private static List<Object> sellerStatementRow(SettlementExportRow s) {
        return Arrays.asList(s.settlementMonth(), s.id(), s.paidAt(),
                s.itemCount(), s.totalSalePrice(), s.totalFee(), s.payoutAmount());
    }

    private static List<Object> adminStatementRow(SettlementExportRow s, String businessName) {
        return Arrays.asList(s.settlementMonth(), s.id(), s.sellerId(), businessName, s.paidAt(), s.walletTxId(),
                s.itemCount(), s.totalSalePrice(), s.totalFee(), s.payoutAmount());
    }

    private static List<Object> itemRow(SettlementExportRow s, SettlementExportItemRow c, boolean admin,
                                        String businessName) {
        List<Object> row = new ArrayList<>(Arrays.asList(s.id(), s.settlementMonth(), c.settlementMonth(),
                !c.settlementMonth().equals(s.settlementMonth()), c.confirmedAt(), c.orderId(), c.orderItemId(),
                c.productName(), c.optionName(), c.feeRate(),
                c.salePrice(), c.productCouponDiscount(), c.fee(), c.payoutAmount()));
        if (admin) {
            row.add(7, s.sellerId());
            row.add(8, businessName);
        }
        return row;
    }

    /** 합계 행 라벨: "합계 (N건)" */
    private static String totalLabel(long count) {
        return "%s (%,d건)".formatted(TOTAL_LABEL, count);
    }

    /** 머리글로 열 번호를 찾는다(열 순서를 바꿔도 합계 위치가 따라간다) */
    private static int indexOf(List<Column> columns, String header) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).header().equals(header)) return i;
        }
        throw new IllegalStateException("엑셀 열이 없습니다: " + header);
    }

    private static String fileName(YearMonth from, YearMonth to) {
        return from.equals(to)
                ? "palette-settlement-%s.xlsx".formatted(from)
                : "palette-settlements-%s_%s.xlsx".formatted(from, to);
    }

    private record ExportResult(Path file, long size, long statementCount, long itemCount) {
    }

    /** 묶음 하나: 정산서 Row와 정산서 ID별 품목 Row */
    private record Chunk(List<SettlementExportRow> statements, Map<Long, List<SettlementExportItemRow>> items) {
        List<SettlementExportItemRow> itemsOf(Long settlementId) {
            return items.getOrDefault(settlementId, List.of());
        }
    }

    /**
     * 상호명 캐시(다운로드 한 번 동안). 처음 보는 판매자 ID만 묶어서 조회하고, 상호명이 없는 판매자도 다시 묻지 않는다
     * 조회가 실패하면 예외를 그대로 던진다
     */
    private final class BusinessNames {
        private final Map<Long, String> names = new HashMap<>();
        private final Set<Long> loaded = new HashSet<>();

        void load(List<SettlementExportRow> statements) {
            List<Long> unseen = statements.stream().map(SettlementExportRow::sellerId).distinct()
                    .filter(id -> !loaded.contains(id)).toList();
            if (unseen.isEmpty()) return;
            names.putAll(settlementSupport.findBusinessNames(unseen));
            loaded.addAll(unseen);
        }

        String get(Long sellerId) {
            return names.get(sellerId);
        }
    }

    /** 합계: 판매가·수수료·지급액 합과 행 수(정산서 시트는 품목 수 합도, 품목 시트는 상품 쿠폰 할인액 합도) */
    private static final class Totals {
        private long sale;
        private long discount;
        private long fee;
        private long payout;
        private long itemCount;
        private long count;

        void add(long sale, long fee, long payout, long itemCount) {
            this.sale += sale;
            this.fee += fee;
            this.payout += payout;
            this.itemCount += itemCount;
            this.count++;
        }

        void addDiscount(long discount) {
            this.discount += discount;
        }

        /** 정산서 시트: 판매 대금·수수료·지급액·품목 수 열 */
        Map<Integer, Long> statementColumns(int saleColumn, int feeColumn, int payoutColumn, int itemCountColumn) {
            return Map.of(saleColumn, sale, feeColumn, fee, payoutColumn, payout, itemCountColumn, itemCount);
        }

        /** 품목 시트: 판매가·상품 쿠폰 할인액·수수료·지급액 열 */
        Map<Integer, Long> itemColumns(int saleColumn, int discountColumn, int feeColumn, int payoutColumn) {
            return Map.of(saleColumn, sale, discountColumn, discount, feeColumn, fee, payoutColumn, payout);
        }
    }
}
