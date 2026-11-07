package lk.aak.agency.dto.api;

import lk.aak.agency.service.SupplierPaymentService.SupplierBalanceSummary;

import java.math.BigDecimal;
import java.util.List;

public class SupplierBalanceResponse {

    private final List<SupplierBalanceRowResponse> rows;
    private final BigDecimal totalOwed;
    private final List<OverpaidInvoice> overpaidInvoices;
    private final BigDecimal totalOverpaid;
    private final BigDecimal creditNotesTotal;
    private final BigDecimal netOwed;
    private final BigDecimal creditInFavour;

    public SupplierBalanceResponse(SupplierBalanceSummary summary) {
        this.rows = summary.rows().stream().map(SupplierBalanceRowResponse::new).toList();
        this.totalOwed = summary.totalOwed();
        this.overpaidInvoices = summary.overpaidRows().stream()
                .map(row -> new OverpaidInvoice(
                        row.invoice().getId(),
                        row.invoice().getDocumentNumber(),
                        row.invoice().getTotalAmount(),
                        row.paidAmount(),
                        row.overpaidAmount()))
                .toList();
        this.totalOverpaid = summary.totalOverpaid();
        this.creditNotesTotal = summary.creditNotesTotal();
        this.netOwed = summary.netOwed();
        this.creditInFavour = summary.creditInFavour();
    }

    /** Approved credit notes from the supplier (they reduce what is owed). */
    public BigDecimal getCreditNotesTotal() {
        return creditNotesTotal;
    }

    /** What is owed after the approved credit notes (never below 0). */
    public BigDecimal getNetOwed() {
        return netOwed;
    }

    /** Credit left over when the approved credit notes are more than what is owed. */
    public BigDecimal getCreditInFavour() {
        return creditInFavour;
    }

    /** Purchase invoices paid more than their total - to be sorted out with CBL; never hidden in a zero balance. */
    public record OverpaidInvoice(
            Long purchaseInvoiceId,
            String documentNumber,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal overpaidAmount) {
    }

    public List<OverpaidInvoice> getOverpaidInvoices() {
        return overpaidInvoices;
    }

    public BigDecimal getTotalOverpaid() {
        return totalOverpaid;
    }

    public List<SupplierBalanceRowResponse> getRows() {
        return rows;
    }

    public BigDecimal getTotalOwed() {
        return totalOwed;
    }
}
