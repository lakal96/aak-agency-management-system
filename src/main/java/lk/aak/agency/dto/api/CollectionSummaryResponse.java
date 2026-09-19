package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class CollectionSummaryResponse {

    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final BigDecimal totalCollected;
    private final BigDecimal totalCash;
    private final BigDecimal totalCheque;
    private final BigDecimal totalBankTransfer;
    private final long cashPaymentCount;
    private final long chequePaymentCount;
    private final long bankTransferPaymentCount;
    private final List<PaymentResponse> payments;

    public CollectionSummaryResponse(
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal totalCollected,
            BigDecimal totalCash,
            BigDecimal totalCheque,
            BigDecimal totalBankTransfer,
            long cashPaymentCount,
            long chequePaymentCount,
            long bankTransferPaymentCount,
            List<PaymentResponse> payments) {

        this.fromDate = fromDate;
        this.toDate = toDate;
        this.totalCollected = totalCollected;
        this.totalCash = totalCash;
        this.totalCheque = totalCheque;
        this.totalBankTransfer = totalBankTransfer;
        this.cashPaymentCount = cashPaymentCount;
        this.chequePaymentCount = chequePaymentCount;
        this.bankTransferPaymentCount = bankTransferPaymentCount;
        this.payments = payments;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public BigDecimal getTotalCollected() {
        return totalCollected;
    }

    public BigDecimal getTotalCash() {
        return totalCash;
    }

    public BigDecimal getTotalCheque() {
        return totalCheque;
    }

    public BigDecimal getTotalBankTransfer() {
        return totalBankTransfer;
    }

    public long getCashPaymentCount() {
        return cashPaymentCount;
    }

    public long getChequePaymentCount() {
        return chequePaymentCount;
    }

    public long getBankTransferPaymentCount() {
        return bankTransferPaymentCount;
    }

    public List<PaymentResponse> getPayments() {
        return payments;
    }
}
