package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.util.List;

public class ChequeDashboardResponse {

    private final List<PaymentResponse> cheques;
    private final long totalCount;
    private final long receivedCount;
    private final long depositedCount;
    private final long clearedCount;
    private final long returnedCount;
    private final long overdueCount;
    private final BigDecimal totalAmount;
    private final BigDecimal pendingAmount;
    private final BigDecimal clearedAmount;
    private final BigDecimal returnedAmount;

    public ChequeDashboardResponse(
            List<PaymentResponse> cheques,
            long totalCount,
            long receivedCount,
            long depositedCount,
            long clearedCount,
            long returnedCount,
            long overdueCount,
            BigDecimal totalAmount,
            BigDecimal pendingAmount,
            BigDecimal clearedAmount,
            BigDecimal returnedAmount) {

        this.cheques = cheques;
        this.totalCount = totalCount;
        this.receivedCount = receivedCount;
        this.depositedCount = depositedCount;
        this.clearedCount = clearedCount;
        this.returnedCount = returnedCount;
        this.overdueCount = overdueCount;
        this.totalAmount = totalAmount;
        this.pendingAmount = pendingAmount;
        this.clearedAmount = clearedAmount;
        this.returnedAmount = returnedAmount;
    }

    public List<PaymentResponse> getCheques() {
        return cheques;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getReceivedCount() {
        return receivedCount;
    }

    public long getDepositedCount() {
        return depositedCount;
    }

    public long getClearedCount() {
        return clearedCount;
    }

    public long getReturnedCount() {
        return returnedCount;
    }

    public long getOverdueCount() {
        return overdueCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getPendingAmount() {
        return pendingAmount;
    }

    public BigDecimal getClearedAmount() {
        return clearedAmount;
    }

    public BigDecimal getReturnedAmount() {
        return returnedAmount;
    }
}
