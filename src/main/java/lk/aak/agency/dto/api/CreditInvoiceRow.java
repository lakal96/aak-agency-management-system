package lk.aak.agency.dto.api;

import lk.aak.agency.model.SalesInvoice;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CreditInvoiceRow {

    private final Long id;
    private final String invoiceNumber;
    private final LocalDate invoiceDate;
    private final LocalDate dueDate;
    private final BigDecimal netAmount;
    private final BigDecimal paidAmount;
    private final BigDecimal balance;
    private final boolean overdue;

    public CreditInvoiceRow(
            SalesInvoice invoice, BigDecimal paidAmount, BigDecimal balance, boolean overdue) {

        this.id = invoice.getId();
        this.invoiceNumber = invoice.getInvoiceNumber();
        this.invoiceDate = invoice.getInvoiceDate();
        this.dueDate = invoice.getDueDate();
        this.netAmount = invoice.getNetAmount();
        this.paidAmount = paidAmount;
        this.balance = balance;
        this.overdue = overdue;
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public boolean isOverdue() {
        return overdue;
    }
}
