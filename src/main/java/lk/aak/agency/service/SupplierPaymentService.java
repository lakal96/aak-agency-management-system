package lk.aak.agency.service;

import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.SupplierPayment;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SupplierPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class SupplierPaymentService {

    private final SupplierPaymentRepository supplierPaymentRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final RowLockService rowLockService;
    private final AuditLogService auditLogService;

    public SupplierPaymentService(
            SupplierPaymentRepository supplierPaymentRepository,
            PurchaseInvoiceRepository purchaseInvoiceRepository,
            RowLockService rowLockService,
            AuditLogService auditLogService) {

        this.supplierPaymentRepository = supplierPaymentRepository;
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.rowLockService = rowLockService;
        this.auditLogService = auditLogService;
    }

    public BigDecimal getPaidAmount(Long purchaseInvoiceId) {
        return zeroIfNull(supplierPaymentRepository.calculatePaidAmount(purchaseInvoiceId));
    }

    public BigDecimal getOutstandingBalance(PurchaseInvoice invoice) {

        BigDecimal totalAmount = zeroIfNull(invoice.getTotalAmount());
        BigDecimal paidAmount = getPaidAmount(invoice.getId());
        BigDecimal balance = totalAmount.subtract(paidAmount);

        return balance.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : balance;
    }

    public List<SupplierPayment> getPaymentsForInvoice(Long purchaseInvoiceId) {
        return supplierPaymentRepository.findByPurchaseInvoiceIdOrderByPaymentDateDesc(purchaseInvoiceId);
    }

    /**
     * Every completed purchase invoice with an outstanding balance still owed to CBL,
     * plus the total owed across all of them - the CBL supplier balance. An invoice that was paid
     * MORE than its total (possible only in data recorded before payments were serialised) never
     * hides inside a zero balance: it is listed separately with the amount overpaid, and is not
     * netted against what is owed on other invoices.
     */
    public SupplierBalanceSummary getSupplierBalanceSummary() {

        List<SupplierBalanceRow> rows = new ArrayList<>();
        List<SupplierOverpaymentRow> overpaidRows = new ArrayList<>();
        BigDecimal totalOwed = BigDecimal.ZERO;
        BigDecimal totalOverpaid = BigDecimal.ZERO;

        for (PurchaseInvoice invoice : purchaseInvoiceRepository.findAllByOrderByInvoiceDateDesc()) {

            if (!"COMPLETED".equalsIgnoreCase(invoice.getStatus())) {
                continue;
            }

            BigDecimal paid = getPaidAmount(invoice.getId());
            BigDecimal balance = zeroIfNull(invoice.getTotalAmount()).subtract(paid);

            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                rows.add(new SupplierBalanceRow(invoice, balance));
                totalOwed = totalOwed.add(balance);

            } else if (balance.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal overpaid = balance.negate();
                overpaidRows.add(new SupplierOverpaymentRow(invoice, paid, overpaid));
                totalOverpaid = totalOverpaid.add(overpaid);
            }
        }

        return new SupplierBalanceSummary(rows, totalOwed, overpaidRows, totalOverpaid);
    }

    /*
     * The purchase invoice row is locked BEFORE the balance is read, and the balance is read at
     * READ_COMMITTED so it includes every payment another request has just committed: several
     * simultaneous payments for the same invoice queue up and only as many succeed as the invoice
     * can actually take.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SupplierPayment recordPayment(SupplierPayment payment) {

        if (payment == null || payment.getPurchaseInvoiceId() == null) {
            throw new IllegalArgumentException("Please select a purchase invoice.");
        }

        rowLockService.lockPurchaseInvoice(payment.getPurchaseInvoiceId());

        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(payment.getPurchaseInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Selected purchase invoice was not found."));

        if (!"COMPLETED".equalsIgnoreCase(invoice.getStatus())) {
            throw new IllegalArgumentException(
                    "Only a completed purchase invoice can have a supplier payment recorded against it."
            );
        }

        BigDecimal amount = payment.getAmount();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }

        amount = amount.setScale(2, RoundingMode.HALF_UP);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }

        payment.setAmount(amount);

        // Read AFTER the lock: this is the balance including every earlier, already committed payment.
        BigDecimal outstanding = getOutstandingBalance(invoice);

        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "This purchase invoice is already fully paid."
            );
        }

        if (amount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException(
                    "Payment amount cannot be greater than the outstanding balance of Rs. "
                            + outstanding.stripTrailingZeros().toPlainString() + "."
            );
        }

        payment.setPurchaseInvoiceDocumentNumber(invoice.getDocumentNumber());

        SupplierPayment saved = supplierPaymentRepository.save(payment);

        auditLogService.record(
                "SUPPLIER_PAYMENT_RECORDED", "SupplierPayment", saved.getId(),
                "Paid Rs. " + amount.toPlainString() + " to CBL against purchase invoice \""
                        + invoice.getDocumentNumber() + "\" ("
                        + (payment.getPaymentMethod() == null ? "method not given" : payment.getPaymentMethod())
                        + ")"
        );

        return saved;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record SupplierBalanceRow(PurchaseInvoice invoice, BigDecimal outstandingBalance) {
    }

    public record SupplierOverpaymentRow(PurchaseInvoice invoice, BigDecimal paidAmount, BigDecimal overpaidAmount) {
    }

    public record SupplierBalanceSummary(
            List<SupplierBalanceRow> rows,
            BigDecimal totalOwed,
            List<SupplierOverpaymentRow> overpaidRows,
            BigDecimal totalOverpaid) {

        public SupplierBalanceSummary(List<SupplierBalanceRow> rows, BigDecimal totalOwed) {
            this(rows, totalOwed, List.of(), BigDecimal.ZERO);
        }
    }
}
