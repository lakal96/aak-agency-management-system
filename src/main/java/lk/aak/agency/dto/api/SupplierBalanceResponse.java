package lk.aak.agency.dto.api;

import lk.aak.agency.service.SupplierPaymentService.SupplierBalanceSummary;

import java.math.BigDecimal;
import java.util.List;

public class SupplierBalanceResponse {

    private final List<SupplierBalanceRowResponse> rows;
    private final BigDecimal totalOwed;
    private final List<OverpaidInvoice> overpaidInvoices;
    private final BigDecimal totalOverpaid;

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
