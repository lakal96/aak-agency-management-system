package lk.aak.agency.intake;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.time.LocalDateTime;

/**
 * One CBL e-mail (or one PDF in it) as the system saw it. NEEDS_ATTENTION rows stay on the "needs attention" list until a person marks them handled
 * (or until the same PDF is imported later); IMPORTED and EMPTY rows are the record of what arrived.
 */
@Entity
@Table(name = "cbl_email_intake")
public class CblEmailIntake {

    public static final String IMPORTED = "IMPORTED";
    public static final String EMPTY = "EMPTY";
    public static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = BusinessTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = BusinessTime.now();

    @Column(nullable = false)
    private int attempts = 1;

    /** INVOICE, CREDIT_NOTES, or OTHER (an e-mail from CBL the script could not even use). */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "email_subject", length = 300)
    private String emailSubject;

    @Column(name = "email_sender", length = 200)
    private String emailSender;

    /** When CBL's e-mail was sent (from Gmail), which can be well before the system received it. */
    @Column(name = "email_sent_at")
    private LocalDateTime emailSentAt;

    /** Imported, but the PDF lacked some of the usual headings: check every line against it. */
    @Column(name = "layout_warning", length = 1000)
    private String layoutWarning;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "document_number", length = 120)
    private String documentNumber;

    @Column(length = 500)
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String problems;

    @Column(name = "purchase_invoice_id")
    private Long purchaseInvoiceId;

    @Column(name = "stored_file_name", length = 255)
    private String storedFileName;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    public Long getId() { return id; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getEmailSubject() { return emailSubject; }
    public void setEmailSubject(String emailSubject) { this.emailSubject = emailSubject; }
    public String getEmailSender() { return emailSender; }
    public void setEmailSender(String emailSender) { this.emailSender = emailSender; }
    public LocalDateTime getEmailSentAt() { return emailSentAt; }
    public void setEmailSentAt(LocalDateTime emailSentAt) { this.emailSentAt = emailSentAt; }
    public String getLayoutWarning() { return layoutWarning; }
    public void setLayoutWarning(String layoutWarning) { this.layoutWarning = layoutWarning; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String messageKey) { this.messageKey = messageKey; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getProblems() { return problems; }
    public void setProblems(String problems) { this.problems = problems; }
    public Long getPurchaseInvoiceId() { return purchaseInvoiceId; }
    public void setPurchaseInvoiceId(Long purchaseInvoiceId) { this.purchaseInvoiceId = purchaseInvoiceId; }
    public String getStoredFileName() { return storedFileName; }
    public void setStoredFileName(String storedFileName) { this.storedFileName = storedFileName; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
}
