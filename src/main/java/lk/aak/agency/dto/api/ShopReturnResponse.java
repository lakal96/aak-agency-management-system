package lk.aak.agency.dto.api;

import lk.aak.agency.model.ShopReturn;

import java.math.BigDecimal;
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
    private final BigDecimal creditAppliedAmount;
    private final BigDecimal refundDueAmount;
    private final String creditMessage;

    public ShopReturnResponse(ShopReturn shopReturn) {
        this.creditAppliedAmount = shopReturn.getCreditAppliedAmount();
        this.refundDueAmount = shopReturn.getRefundDueAmount();
        this.creditMessage = buildCreditMessage(shopReturn);
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

    private static String buildCreditMessage(ShopReturn shopReturn) {

        if (!"APPROVED".equalsIgnoreCase(shopReturn.getStatus()) || shopReturn.getCreditAppliedAmount() == null) {
            return null;
        }

        String message = "Rs. " + shopReturn.getCreditAppliedAmount().toPlainString()
                + " was credited to the shop's bill.";

        if (shopReturn.getRefundDueAmount() != null && shopReturn.getRefundDueAmount().signum() > 0) {
            message += " The shop had already paid for these goods: Rs. "
                    + shopReturn.getRefundDueAmount().toPlainString()
                    + " is due back to the shop (no refund was created automatically).";
        }

        return message;
    }

    public BigDecimal getCreditAppliedAmount() {
        return creditAppliedAmount;
    }

    public BigDecimal getRefundDueAmount() {
        return refundDueAmount;
    }

    public String getCreditMessage() {
        return creditMessage;
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
