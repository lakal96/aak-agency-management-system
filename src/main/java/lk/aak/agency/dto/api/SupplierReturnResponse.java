package lk.aak.agency.dto.api;

import lk.aak.agency.model.SupplierReturn;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SupplierReturnResponse {

    private final Long id;
    private final Long purchaseInvoiceId;
    private final String purchaseInvoiceDocumentNumber;
    private final LocalDate returnDate;
    private final String reason;
    private final String referenceNumber;
    private final String notes;
    private final String status;
    private final LocalDateTime createdAt;

    public SupplierReturnResponse(SupplierReturn supplierReturn) {
        this.id = supplierReturn.getId();
        this.purchaseInvoiceId = supplierReturn.getPurchaseInvoiceId();
        this.purchaseInvoiceDocumentNumber = supplierReturn.getPurchaseInvoiceDocumentNumber();
        this.returnDate = supplierReturn.getReturnDate();
        this.reason = supplierReturn.getReason();
        this.referenceNumber = supplierReturn.getReferenceNumber();
        this.notes = supplierReturn.getNotes();
        this.status = supplierReturn.getStatus();
        this.createdAt = supplierReturn.getCreatedAt();
    }

    public Long getId() {
        return id;
    }

    public Long getPurchaseInvoiceId() {
        return purchaseInvoiceId;
    }

    public String getPurchaseInvoiceDocumentNumber() {
        return purchaseInvoiceDocumentNumber;
    }

    public LocalDate getReturnDate() {
        return returnDate;
    }

    public String getReason() {
        return reason;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public String getNotes() {
        return notes;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
