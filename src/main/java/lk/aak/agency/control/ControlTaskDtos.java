package lk.aak.agency.control;

import lk.aak.agency.control.ControlTaskDefs.Column;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the task API sends and receives. */
public final class ControlTaskDtos {

    private ControlTaskDtos() { }

    public record TaskView(String id, String code, String title, String slot, String scope, String vehicle, boolean vanSales, String input,
                           String entryKind, String entryTitle, List<Column> columns, boolean optional, String person, String screen,
                           List<String> steps, String proof, String status, boolean overdue, String doneBy, LocalDateTime doneAt, String note,
                           List<String> blockers) { }

    public record LorryState(String vehicle, boolean vanSales, boolean active, boolean noRun, boolean hasBills) { }

    public record DayTasks(LocalDate date, String closedReason, boolean worked, boolean future, boolean beforeStart, int required, int done,
                           boolean overdue, boolean signed, String signedBy, List<LorryState> lorries, List<TaskView> tasks) { }

    public record CalendarDay(LocalDate date, String state, int required, int done, String closedReason, boolean signed) { }

    public record BacklogDay(LocalDate date, int open, int required) { }

    public record Backlog(LocalDate startDate, int days, int tasks, List<BacklogDay> list) { }

    public record EntryLine(String a, String b, Integer qty, BigDecimal amount) { }

    public record EntriesView(LocalDate date, String vehicle, String kind, String title, List<Column> columns, List<EntryLine> lines) { }

    public record DoneRequest(String taskCode, String vehicle, String doneBy, String note) { }

    public record NoRunRequest(String vehicle, String doneBy) { }

    public record BillsRequest(String repName, String driverName, List<ControlDtos.BillIn> bills) { }

    public record CashRequest(BigDecimal cashCountedRep, BigDecimal cashCountedDriver) { }

    public record TabletRequest(String tabletStockMorning, String morningStockOk, String tabletStockAfter) { }

    public record StockRequest(List<ControlDtos.StockIn> stock, String tabletStockAfter) { }

    // ---------------------------------------------------------------- the dashboard in numbers

    public record Totals(int bills, BigDecimal billValue, BigDecimal cash, BigDecimal cheque, BigDecimal credit, BigDecimal oldCreditCollected,
                         BigDecimal chequesReceived, int cancelledBills, BigDecimal cancelledValue, int damagedPacks) { }

    public record LorryNumbers(String vehicle, boolean vanSales, int days, int bills, BigDecimal billValue, BigDecimal cash, BigDecimal cheque, BigDecimal credit) { }

    public record DayNumbers(LocalDate date, int bills, BigDecimal billValue, BigDecimal cash, BigDecimal cheque, BigDecimal credit) { }

    public record TaskNumbers(int required, int done, int percent, int overdueTasks, int overdueDays, LocalDate oldestOverdue, int todayRequired, int todayDone) { }

    public record FocusItem(String severity, String title, String detail, String link) { }

    public record Numbers(LocalDate from, LocalDate to, LocalDate startDate, Totals totals, List<LorryNumbers> lorries, List<DayNumbers> days, TaskNumbers tasks,
                          int failingChecks, int vanTabletDays, int vanStockDays, int unsignedDays, List<FocusItem> focus) { }

    /** What the bell needs: how far behind the work is. */
    public record Progress(int overdueTasks, int overdueDays, LocalDate oldestOverdue, int todayRequired, int todayDone) { }
}
