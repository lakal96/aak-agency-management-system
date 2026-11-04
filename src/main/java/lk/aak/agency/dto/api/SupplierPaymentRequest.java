package lk.aak.agency.dto.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lk.aak.agency.util.Decimals;

import java.math.BigDecimal;
import java.time.LocalDate;

public class SupplierPaymentRequest {

    @NotNull
    private Long purchaseInvoiceId;

    @NotNull
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero.")
    @DecimalMax(value = "999999999.99", message = "Payment amount is too large - please check the number.")
    private BigDecimal amount;

    @NotNull(message = "Payment date is required.")
    @NotFutureDate(message = "The payment date cannot be in the future.")
    private LocalDate paymentDate;

    @NotBlank(message = "Select a payment method.")
    @Pattern(regexp = "CASH|CHEQUE|BANK_TRANSFER", message = "The payment method must be Cash, Cheque or Bank transfer.")
    private String paymentMethod;

    private String referenceNumber;
    private String notes;

    public Long getPurchaseInvoiceId() {
        return purchaseInvoiceId;
    }

    public void setPurchaseInvoiceId(Long purchaseInvoiceId) {
        this.purchaseInvoiceId = purchaseInvoiceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = Decimals.round2(amount);
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod == null ? null : paymentMethod.trim().toUpperCase();
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
