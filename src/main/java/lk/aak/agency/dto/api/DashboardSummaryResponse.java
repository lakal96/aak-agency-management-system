package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.util.List;

public class DashboardSummaryResponse {

    private final long totalCustomers;
    private final long activeCustomers;
    private final BigDecimal salesThisMonth;
    private final BigDecimal outstandingReceivables;
    private final long outstandingInvoiceCount;
    private final long totalProducts;
    private final BigDecimal todaySales;
    private final BigDecimal todayCollections;
    private final long lowStockCount;
    private final long outOfStockCount;
    private final BigDecimal totalOwedToSupplier;
    private final long pendingChequeCount;
    private final List<RecentActivity> recentActivity;

    public DashboardSummaryResponse(
            long totalCustomers,
            long activeCustomers,
            BigDecimal salesThisMonth,
            BigDecimal outstandingReceivables,
            long outstandingInvoiceCount,
            long totalProducts,
            BigDecimal todaySales,
            BigDecimal todayCollections,
            long lowStockCount,
            long outOfStockCount,
            BigDecimal totalOwedToSupplier,
            long pendingChequeCount,
            List<RecentActivity> recentActivity) {

        this.totalCustomers = totalCustomers;
        this.activeCustomers = activeCustomers;
        this.salesThisMonth = salesThisMonth;
        this.outstandingReceivables = outstandingReceivables;
        this.outstandingInvoiceCount = outstandingInvoiceCount;
        this.totalProducts = totalProducts;
        this.todaySales = todaySales;
        this.todayCollections = todayCollections;
        this.lowStockCount = lowStockCount;
        this.outOfStockCount = outOfStockCount;
        this.totalOwedToSupplier = totalOwedToSupplier;
        this.pendingChequeCount = pendingChequeCount;
        this.recentActivity = recentActivity;
    }

    public long getTotalCustomers() {
        return totalCustomers;
    }

    public long getActiveCustomers() {
        return activeCustomers;
    }

    public BigDecimal getSalesThisMonth() {
        return salesThisMonth;
    }

    public BigDecimal getOutstandingReceivables() {
        return outstandingReceivables;
    }

    public long getOutstandingInvoiceCount() {
        return outstandingInvoiceCount;
    }

    public long getTotalProducts() {
        return totalProducts;
    }

    public BigDecimal getTodaySales() {
        return todaySales;
    }

    public BigDecimal getTodayCollections() {
        return todayCollections;
    }

    public long getLowStockCount() {
        return lowStockCount;
    }

    public long getOutOfStockCount() {
        return outOfStockCount;
    }

    public BigDecimal getTotalOwedToSupplier() {
        return totalOwedToSupplier;
    }

    public long getPendingChequeCount() {
        return pendingChequeCount;
    }

    public List<RecentActivity> getRecentActivity() {
        return recentActivity;
    }

    public record RecentActivity(
            String username,
            String action,
            String entityType,
            Long entityId,
            String details,
            java.time.LocalDateTime occurredAt) {
    }
}
