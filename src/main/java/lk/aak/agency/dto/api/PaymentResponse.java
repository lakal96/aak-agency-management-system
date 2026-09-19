package lk.aak.agency.dto.api;

import lk.aak.agency.model.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;

public class PaymentResponse {

    private final Long id;
    private final String receiptNumber;
    private final Long salesInvoiceId;
    private final String salesInvoiceNumber;
    private final String customerName;
    private final LocalDate paymentDate;
    private final BigDecimal amount;
    private final String paymentMethod;
    private final String chequeNumber;
    private final LocalDate chequeDate;
    private final String bankName;
    private final String status;
    private final String chequeStatus;
    private final String notes;

    public PaymentResponse(Payment payment) {
        this.id = payment.getId();
        this.receiptNumber = payment.getReceiptNumber();
        this.salesInvoiceId = payment.getSalesInvoice() != null ? payment.getSalesInvoice().getId() : null;
        this.salesInvoiceNumber = payment.getSalesInvoice() != null ? payment.getSalesInvoice().getInvoiceNumber() : null;
        this.customerName = payment.getSalesInvoice() != null && payment.getSalesInvoice().getCustomer() != null
                ? payment.getSalesInvoice().getCustomer().getCustomerName()
                : null;
        this.paymentDate = payment.getPaymentDate();
        this.amount = payment.getAmount();
        this.paymentMethod = payment.getPaymentMethod();
        this.chequeNumber = payment.getChequeNumber();
        this.chequeDate = payment.getChequeDate();
        this.bankName = payment.getBankName();
        this.status = payment.getStatus();
        this.chequeStatus = payment.getChequeStatus();
        this.notes = payment.getNotes();
    }

    public Long getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public Long getSalesInvoiceId() {
        return salesInvoiceId;
    }

    public String getSalesInvoiceNumber() {
        return salesInvoiceNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getChequeNumber() {
        return chequeNumber;
    }

    public LocalDate getChequeDate() {
        return chequeDate;
    }

    public String getBankName() {
        return bankName;
    }

    public String getStatus() {
        return status;
    }

    public String getChequeStatus() {
        return chequeStatus;
    }

    public String getNotes() {
        return notes;
    }
}
