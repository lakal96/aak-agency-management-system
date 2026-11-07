package lk.aak.agency.credit;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A credit note from the supplier. DRAFT until a person checks it; APPROVED notes reduce what is owed to the supplier. */
@Entity
@Table(name = "supplier_credit_notes", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_credit_note_number", columnNames = "document_number"))
public class SupplierCreditNote {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_number", nullable = false, length = 100)
    private String documentNumber;

    @Column(name = "issuer_name", nullable = false, length = 200)
    private String issuerName;

    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;

    @Column(name = "ref_invoice_number", length = 100)
    private String refInvoiceNumber;

    @Column(name = "ref_invoice_date")
    private LocalDate refInvoiceDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 500)
    private String remarks;

    @Column(nullable = false, length = 20)
    private String status = DRAFT;

    @Column(nullable = false, length = 20)
    private String source = "MANUAL";

    @Column(name = "file_original_name", length = 255)
    private String fileOriginalName;

    @Column(name = "file_stored_name", length = 255)
    private String fileStoredName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = BusinessTime.now();

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public Long getId() { return id; }
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public String getIssuerName() { return issuerName; }
    public void setIssuerName(String issuerName) { this.issuerName = issuerName; }
    public LocalDate getNoteDate() { return noteDate; }
    public void setNoteDate(LocalDate noteDate) { this.noteDate = noteDate; }
    public String getRefInvoiceNumber() { return refInvoiceNumber; }
    public void setRefInvoiceNumber(String refInvoiceNumber) { this.refInvoiceNumber = refInvoiceNumber; }
    public LocalDate getRefInvoiceDate() { return refInvoiceDate; }
    public void setRefInvoiceDate(LocalDate refInvoiceDate) { this.refInvoiceDate = refInvoiceDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getFileOriginalName() { return fileOriginalName; }
    public void setFileOriginalName(String fileOriginalName) { this.fileOriginalName = fileOriginalName; }
    public String getFileStoredName() { return fileStoredName; }
    public void setFileStoredName(String fileStoredName) { this.fileStoredName = fileStoredName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public boolean hasFile() { return fileStoredName != null && !fileStoredName.isBlank(); }
}
