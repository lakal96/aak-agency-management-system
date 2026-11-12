package lk.aak.agency.credit;

import lk.aak.agency.service.AuditLogService;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.util.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Credit notes from the supplier: entered by hand or read from the e-mailed PDF, approved by a person, then they count. */
@Service
public class SupplierCreditNoteService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    private final SupplierCreditNoteRepository repository;
    private final AuditLogService auditLogService;
    private final PurchaseInvoiceFileService fileService;

    public SupplierCreditNoteService(SupplierCreditNoteRepository repository, AuditLogService auditLogService,
                                     PurchaseInvoiceFileService fileService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.fileService = fileService;
    }

    public List<SupplierCreditNote> list() {
        return repository.findAllByOrderByNoteDateDescIdDesc();
    }

    public SupplierCreditNote get(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Credit note not found."));
    }

    /** A credit note typed in by hand (a paper one). It starts as a DRAFT like every other. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SupplierCreditNote create(String documentNumber, String issuerName, LocalDate noteDate, BigDecimal amount,
                                     String remarks, String username) {

        String number = documentNumber == null ? "" : documentNumber.trim();
        if (number.isEmpty()) throw new IllegalArgumentException("Enter the credit note number.");
        if (number.length() > 100) throw new IllegalArgumentException("The credit note number is too long.");
        if (noteDate == null) throw new IllegalArgumentException("Enter the date of the credit note.");
        BigDecimal value = validAmount(amount);
        if (repository.findByDocumentNumber(number).isPresent()) {
            throw new IllegalArgumentException("A credit note with the number " + number + " is already recorded.");
        }

        SupplierCreditNote note = new SupplierCreditNote();
        note.setDocumentNumber(number);
        note.setIssuerName(issuerName == null || issuerName.isBlank() ? "CBL Foods International (Pvt) Ltd" : issuerName.trim());
        note.setNoteDate(noteDate);
        note.setAmount(value);
        note.setRemarks(remarks == null || remarks.isBlank() ? null : remarks.trim());
        note.setSource("MANUAL");
        SupplierCreditNote saved = repository.save(note);
        auditLogService.recordAs(username, "SUPPLIER_CREDIT_NOTE_ADDED", "SupplierCreditNote", saved.getId(),
                "Credit note " + number + " Rs " + value + " (draft)");
        return saved;
    }

    public record ImportResult(List<SupplierCreditNote> created, List<String> duplicates) {
    }

    /** The notes read from an e-mailed PDF become drafts (those already recorded are skipped). The PDF is attached to each. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportResult importNotes(List<CreditNoteParser.Note> notes, MultipartFile pdf, String username) {

        List<SupplierCreditNote> created = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        PurchaseInvoiceFileService.StoredInvoiceFile stored = null;

        for (CreditNoteParser.Note parsed : notes) {
            if (repository.findByDocumentNumber(parsed.documentNumber()).isPresent()) {
                duplicates.add(parsed.documentNumber());
                continue;
            }
            if (stored == null) stored = fileService.storeFile(pdf);

            SupplierCreditNote note = new SupplierCreditNote();
            note.setDocumentNumber(parsed.documentNumber());
            note.setIssuerName(parsed.issuer());
            note.setNoteDate(parsed.date());
            note.setRefInvoiceNumber(parsed.refInvoiceNumber());
            note.setRefInvoiceDate(parsed.refInvoiceDate());
            note.setAmount(validAmount(parsed.amount()));
            String remarks = parsed.remarks() == null ? "" : parsed.remarks();
            note.setRemarks(remarks.isBlank() ? null : remarks.length() > 500 ? remarks.substring(0, 500) : remarks);
            note.setSource("EMAIL");
            note.setFileOriginalName(stored.originalFileName());
            note.setFileStoredName(stored.storedFileName());
            created.add(repository.save(note));
            auditLogService.recordAs(username, "SUPPLIER_CREDIT_NOTE_EMAILED", "SupplierCreditNote", note.getId(),
                    "Draft from the e-mailed credit note " + note.getDocumentNumber() + " Rs " + note.getAmount() + (note.getRemarks() == null ? "" : " (" + note.getRemarks() + ")"));
        }
        return new ImportResult(created, duplicates);
    }

    /**
     * A person has compared the note with the original and approves it: from now it reduces what is owed. The reader can be wrong if CBL changes how its
     * credit notes look, so the person must say, each time, that they compared the figures with the original (the system cannot check the amount itself).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SupplierCreditNote approve(Long id, String username, boolean comparedWithOriginal) {
        if (!comparedWithOriginal) {
            throw new IllegalArgumentException("Compare the credit note's number, date and amount with the original (the PDF or the paper from CBL), then tick the box to approve it.");
        }
        SupplierCreditNote note = repository.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Credit note not found."));
        if (SupplierCreditNote.APPROVED.equals(note.getStatus())) {
            throw new IllegalArgumentException("This credit note is already approved.");
        }
        note.setStatus(SupplierCreditNote.APPROVED);
        note.setApprovedBy(username);
        note.setApprovedAt(BusinessTime.now());
        SupplierCreditNote saved = repository.save(note);
        auditLogService.record("SUPPLIER_CREDIT_NOTE_APPROVED", "SupplierCreditNote", id,
                "Approved credit note " + note.getDocumentNumber() + " Rs " + note.getAmount() + " (compared with the original by " + username + ")");
        return saved;
    }

    /** Only a draft can be removed (an approved note is part of the books). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDraft(Long id) {
        SupplierCreditNote note = repository.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Credit note not found."));
        if (!SupplierCreditNote.DRAFT.equals(note.getStatus())) {
            throw new IllegalArgumentException("An approved credit note cannot be deleted.");
        }
        repository.delete(note);
        auditLogService.record("SUPPLIER_CREDIT_NOTE_DELETED", "SupplierCreditNote", id,
                "Deleted draft credit note " + note.getDocumentNumber() + " Rs " + note.getAmount());
    }

    private static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("The credit note amount must be more than 0.");
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (value.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException("The credit note amount is too large - please check the number.");
        return value;
    }
}
