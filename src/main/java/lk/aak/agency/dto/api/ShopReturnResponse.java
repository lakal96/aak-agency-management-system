package lk.aak.agency.dto.api;

import lk.aak.agency.model.ShopReturn;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class ShopReturnResponse {

    private final Long id;
    private final Long customerId;
    private final String customerName;
    private final Long salesInvoiceId;
    private final LocalDate returnDate;
    private final String reason;
    private final String notes;
    private final String status;
    private final LocalDateTime createdAt;

    public ShopReturnResponse(ShopReturn shopReturn) {
        this.id = shopReturn.getId();
        this.customerId = shopReturn.getCustomerId();
        this.customerName = shopReturn.getCustomerName();
        this.salesInvoiceId = shopReturn.getSalesInvoiceId();
        this.returnDate = shopReturn.getReturnDate();
        this.reason = shopReturn.getReason();
        this.notes = shopReturn.getNotes();
        this.status = shopReturn.getStatus();
        this.createdAt = shopReturn.getCreatedAt();
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public Long getSalesInvoiceId() {
        return salesInvoiceId;
    }

    public LocalDate getReturnDate() {
        return returnDate;
    }

    public String getReason() {
        return reason;
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
