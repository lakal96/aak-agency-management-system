package lk.aak.agency.dto.api;

import lk.aak.agency.service.SupplierPaymentService.SupplierBalanceRow;

import java.math.BigDecimal;

public class SupplierBalanceRowResponse {

    private final Long purchaseInvoiceId;
    private final String documentNumber;
    private final String supplierName;
    private final BigDecimal totalAmount;
    private final BigDecimal outstandingBalance;

    public SupplierBalanceRowResponse(SupplierBalanceRow row) {
        this.purchaseInvoiceId = row.invoice().getId();
        this.documentNumber = row.invoice().getDocumentNumber();
        this.supplierName = row.invoice().getSupplierName();
        this.totalAmount = row.invoice().getTotalAmount();
        this.outstandingBalance = row.outstandingBalance();
    }

    public Long getPurchaseInvoiceId() {
        return purchaseInvoiceId;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getOutstandingBalance() {
        return outstandingBalance;
    }
}
