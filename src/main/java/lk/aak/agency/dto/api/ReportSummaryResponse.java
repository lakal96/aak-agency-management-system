package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public class ReportSummaryResponse {

    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final BigDecimal totalSales;
    private final BigDecimal totalPurchases;
    private final BigDecimal totalPayments;
    private final BigDecimal outstandingCredit;
    private final BigDecimal totalOwedToSupplier;
    private final BigDecimal totalShopReturnsValue;
    private final BigDecimal totalSupplierReturnsValue;
    private final long completedSalesCount;
    private final long completedPurchaseCount;
    private final long receivedPaymentCount;
    private final long lowStockCount;
    private final long outOfStockCount;

    public ReportSummaryResponse(
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal totalSales,
            BigDecimal totalPurchases,
            BigDecimal totalPayments,
            BigDecimal outstandingCredit,
            BigDecimal totalOwedToSupplier,
            BigDecimal totalShopReturnsValue,
            BigDecimal totalSupplierReturnsValue,
            long completedSalesCount,
            long completedPurchaseCount,
            long receivedPaymentCount,
            long lowStockCount,
            long outOfStockCount) {

        this.fromDate = fromDate;
        this.toDate = toDate;
        this.totalSales = totalSales;
        this.totalPurchases = totalPurchases;
        this.totalPayments = totalPayments;
        this.outstandingCredit = outstandingCredit;
        this.totalOwedToSupplier = totalOwedToSupplier;
        this.totalShopReturnsValue = totalShopReturnsValue;
        this.totalSupplierReturnsValue = totalSupplierReturnsValue;
        this.completedSalesCount = completedSalesCount;
        this.completedPurchaseCount = completedPurchaseCount;
        this.receivedPaymentCount = receivedPaymentCount;
        this.lowStockCount = lowStockCount;
        this.outOfStockCount = outOfStockCount;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public BigDecimal getTotalSales() {
        return totalSales;
    }

    public BigDecimal getTotalPurchases() {
        return totalPurchases;
    }

    public BigDecimal getTotalPayments() {
        return totalPayments;
    }

    public BigDecimal getOutstandingCredit() {
        return outstandingCredit;
    }

    public BigDecimal getTotalOwedToSupplier() {
        return totalOwedToSupplier;
    }

    public BigDecimal getTotalShopReturnsValue() {
        return totalShopReturnsValue;
    }

    public BigDecimal getTotalSupplierReturnsValue() {
        return totalSupplierReturnsValue;
    }

    public long getCompletedSalesCount() {
        return completedSalesCount;
    }

    public long getCompletedPurchaseCount() {
        return completedPurchaseCount;
    }

    public long getReceivedPaymentCount() {
        return receivedPaymentCount;
    }

    public long getLowStockCount() {
        return lowStockCount;
    }

    public long getOutOfStockCount() {
        return outOfStockCount;
    }
}
