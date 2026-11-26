package lk.aak.agency.control;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the Control API sends and receives. */
public final class ControlDtos {

    private ControlDtos() { }

    public record BillIn(String billNo, BigDecimal billValue, BigDecimal cashRep, BigDecimal cashDriver, BigDecimal chequeAmount, String chequeNo, BigDecimal creditAmount) { }

    public record StockIn(String productName, Integer counted, Integer loadTomorrow) { }

    public record LorryRequest(String repName, String driverName, BigDecimal cashCountedRep, BigDecimal cashCountedDriver,
                               String tabletStockMorning, String tabletStockAfter, String morningStockOk,
                               List<BillIn> bills, List<StockIn> stock) { }

    public record BookRequest(BigDecimal safeOpening, BigDecimal banked, BigDecimal paidOut, BigDecimal safeCounted, boolean dayEndDone) { }

    public record ClosureRequest(String checkCode, String note) { }

    public record ClosureView(String note, String closedBy, LocalDateTime closedAt) { }

    public record CheckView(String code, String title, String status, String paper, String comparedWith, String compared, String detail, ClosureView acknowledged) { }

    public record UploadView(Long id, String kind, String originalName, String status, String message, Integer rowsRead, String uploadedBy, LocalDateTime uploadedAt, boolean inUse) { }

    public record Counts(int failing, int acknowledged, int waiting, int passing) { }

    public record LorryView(LocalDate date, String vehicle, boolean vanSales, boolean hasPaper, String repName, String driverName,
                            BigDecimal cashCountedRep, BigDecimal cashCountedDriver, String tabletStockMorning, String tabletStockAfter,
                            String morningStockOk, List<BillIn> bills, List<StockIn> stock, List<UploadView> uploads,
                            List<CheckView> checks, Counts counts, String updatedBy, LocalDateTime updatedAt) { }

    public record BookView(LocalDate date, BigDecimal safeOpening, BigDecimal banked, BigDecimal paidOut, BigDecimal safeCounted,
                           boolean dayEndDone, String signedBy, LocalDateTime signedAt, BigDecimal cashCountedAll, List<CheckView> checks, Counts counts) { }

    public record LorrySummary(String vehicle, boolean vanSales, boolean hasPaper, boolean hasRdSales, boolean hasStockBalance,
                               Counts counts, String light, List<CheckView> problems) { }

    public record DaySummary(LocalDate date, String closedReason, List<LorrySummary> lorries, BookView book, String light, boolean missing) { }
}
