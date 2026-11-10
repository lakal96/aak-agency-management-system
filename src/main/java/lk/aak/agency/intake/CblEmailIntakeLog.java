package lk.aak.agency.intake;

import lk.aak.agency.service.AuditLogService;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.util.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The system's own record of every CBL e-mail it receives, so that a person can see - inside the system, not only in Gmail - what was
 * imported and, above all, what <b>needs attention</b>: a PDF the system refused (kept here so it can be opened), an e-mail the Gmail script could
 * not use at all, or a Gmail script that has stopped checking.
 *
 * The Gmail script retries refused e-mails for a few days, so the same PDF arrives many times: it is recognised by its content and shown once.
 */
@Service
public class CblEmailIntakeLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(CblEmailIntakeLog.class);

    public static final String CHECKER = "GMAIL_CHECKER";
    /** The script checks every 30 minutes; three hours of silence is a stopped script, not a quiet inbox. */
    static final Duration CHECKER_STALE_AFTER = Duration.ofHours(3);

    public record Summary(long needsAttention, LocalDateTime lastImportedAt, LocalDateTime lastCheckedAt, String checkerStatus) {
    }

    private final CblEmailIntakeRepository repository;
    private final IntegrationHeartbeatRepository heartbeats;
    private final PurchaseInvoiceFileService fileService;
    private final AuditLogService auditLogService;

    public CblEmailIntakeLog(CblEmailIntakeRepository repository, IntegrationHeartbeatRepository heartbeats,
                             PurchaseInvoiceFileService fileService, AuditLogService auditLogService) {
        this.repository = repository;
        this.heartbeats = heartbeats;
        this.fileService = fileService;
        this.auditLogService = auditLogService;
    }

    /** Called with whatever the intake answered for a PDF. Never throws: keeping the record must not change the answer. */
    @Transactional
    public void recordOutcome(CblInvoiceIntakeService.Result result, byte[] pdf, String fileName, String subject) {
        try {
            String hash = sha256(pdf);
            String kind = result.kind() == null ? "INVOICE" : result.kind();
            boolean credit = "CREDIT_NOTES".equals(kind);
            switch (result.status()) {
                case CREATED -> {
                    resolveEarlier(hash, "Imported later: the system can now read it.");
                    CblEmailIntake row = newRow(kind, CblEmailIntake.IMPORTED, fileName, subject, hash);
                    row.setDocumentNumber(result.documentNumber());
                    row.setPurchaseInvoiceId(result.invoiceId());
                    row.setSummary(credit
                            ? result.lines() + " credit note" + (result.lines() == 1 ? "" : "s") + " made as drafts, Rs " + plain(result.total())
                            : "Draft purchase invoice: " + result.lines() + " lines, Rs " + plain(result.total()));
                    row.setResolved(true);
                    row.setResolvedBy("system");
                    row.setResolvedAt(BusinessTime.now());
                    repository.save(row);
                    auditLogService.recordAs(CblInvoiceIntakeService.INTAKE_USER, "CBL_EMAIL_IMPORTED", "CblEmail", row.getId(),
                            "CBL " + (credit ? "credit note file " : "invoice ") + (result.documentNumber() == null ? "" : result.documentNumber() + " ")
                                    + "arrived by e-mail and was made into a draft (" + row.getSummary() + "). Check it and complete it.");
                }
                case DUPLICATE -> resolveEarlier(hash, "It is already in the system.");
                case EMPTY -> {
                    resolveEarlier(hash, "Nothing to import: it has no products on it.");
                    if (repository.findFirstByFileHashAndStatusOrderByIdDesc(hash, CblEmailIntake.EMPTY).isEmpty()) {
                        CblEmailIntake row = newRow(kind, CblEmailIntake.EMPTY, fileName, subject, hash);
                        row.setDocumentNumber(result.documentNumber());
                        row.setSummary("An invoice with no products on it (all totals 0.00): nothing to import.");
                        row.setResolved(true);
                        row.setResolvedBy("system");
                        row.setResolvedAt(BusinessTime.now());
                        repository.save(row);
                    }
                }
                case REJECTED -> needsAttention(kind, fileName, subject, hash, pdf, result.documentNumber(), result.problems());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not record the CBL e-mail outcome: {}", exception.toString());
        }
    }

    /** The Gmail script found an e-mail from CBL it could not use at all (no PDF, a subject it does not know...). Told once per e-mail. */
    @Transactional
    public void recordUnread(String messageKey, String subject, String reason) {
        String key = clip(messageKey == null || messageKey.isBlank() ? subject : messageKey, 100);
        Optional<CblEmailIntake> existing = key == null ? Optional.empty() : repository.findByMessageKey(key);
        if (existing.isPresent()) {
            CblEmailIntake row = existing.get();
            row.setAttempts(row.getAttempts() + 1);
            row.setLastSeenAt(BusinessTime.now());
            repository.save(row);
            return;
        }
        CblEmailIntake row = newRow("OTHER", CblEmailIntake.NEEDS_ATTENTION, null, subject, null);
        row.setMessageKey(key);
        row.setProblems(clip(reason == null || reason.isBlank() ? "The e-mail could not be used." : reason, 2000));
        row.setSummary("An e-mail from CBL that the system could not use.");
        repository.save(row);
        auditLogService.recordAs(CblInvoiceIntakeService.INTAKE_USER, "CBL_EMAIL_NEEDS_ATTENTION", "CblEmail", row.getId(),
                "An e-mail from CBL could not be used" + (subject == null ? "" : " (\"" + clip(subject, 80) + "\")") + ": " + clip(row.getProblems(), 200));
    }

    @Transactional
    public void heartbeat(String detail) {
        IntegrationHeartbeat beat = heartbeats.findById(CHECKER).orElseGet(() -> new IntegrationHeartbeat(CHECKER));
        beat.setLastSeenAt(BusinessTime.now());
        beat.setDetail(clip(detail, 300));
        heartbeats.save(beat);
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDateTime lastImported = repository.findFirstByStatusOrderByIdDesc(CblEmailIntake.IMPORTED).map(CblEmailIntake::getReceivedAt).orElse(null);
        LocalDateTime lastChecked = heartbeats.findById(CHECKER).map(IntegrationHeartbeat::getLastSeenAt).orElse(null);
        String checker = lastChecked == null ? "NEVER"
                : Duration.between(lastChecked, BusinessTime.now()).compareTo(CHECKER_STALE_AFTER) > 0 ? "STALE" : "OK";
        return new Summary(repository.countByStatusAndResolvedFalse(CblEmailIntake.NEEDS_ATTENTION), lastImported, lastChecked, checker);
    }

    @Transactional(readOnly = true)
    public List<CblEmailIntake> list(boolean onlyNeedingAttention, int limit) {
        if (onlyNeedingAttention) {
            return repository.findByStatusAndResolvedFalseOrderByIdDesc(CblEmailIntake.NEEDS_ATTENTION);
        }
        return repository.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    @Transactional(readOnly = true)
    public CblEmailIntake get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("That CBL e-mail record was not found."));
    }

    @Transactional
    public CblEmailIntake resolve(Long id, String username, String note) {
        CblEmailIntake row = get(id);
        if (!row.isResolved()) {
            row.setResolved(true);
            row.setResolvedBy(username);
            row.setResolvedAt(BusinessTime.now());
            row.setResolutionNote(clip(note == null || note.isBlank() ? "Handled by a person." : note.trim(), 500));
            repository.save(row);
            auditLogService.record("CBL_EMAIL_HANDLED", "CblEmail", row.getId(),
                    "Marked a CBL e-mail as handled" + (row.getFileName() == null ? "" : " (" + row.getFileName() + ")") + ": " + row.getResolutionNote());
        }
        return row;
    }

    // ------------------------------------------------------------------------------------------------------------

    private void needsAttention(String kind, String fileName, String subject, String hash, byte[] pdf, String documentNumber, List<String> problems) {
        String text = clip(String.join("\n", problems == null || problems.isEmpty() ? List.of("The file could not be read.") : problems), 4000);
        Optional<CblEmailIntake> existing = repository.findFirstByFileHashAndStatusOrderByIdDesc(hash, CblEmailIntake.NEEDS_ATTENTION);
        if (existing.isPresent()) {
            // the same PDF again (the script retries): count it, and if the reason has changed tell people again
            CblEmailIntake row = existing.get();
            row.setAttempts(row.getAttempts() + 1);
            row.setLastSeenAt(BusinessTime.now());
            if (!text.equals(row.getProblems())) {
                row.setProblems(text);
                row.setResolved(false);
                row.setResolvedBy(null);
                row.setResolvedAt(null);
            }
            repository.save(row);
            return;
        }
        CblEmailIntake row = newRow(kind, CblEmailIntake.NEEDS_ATTENTION, fileName, subject, hash);
        row.setDocumentNumber(documentNumber);
        row.setProblems(text);
        row.setSummary("A CBL " + ("CREDIT_NOTES".equals(kind) ? "credit note file" : "invoice") + " the system could not read exactly, so nothing was imported.");
        try {
            row.setStoredFileName(fileService.storeFile(new ByteArrayMultipartFile(safeName(fileName), pdf)).storedFileName());
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not keep the refused CBL PDF: {}", exception.toString());
        }
        repository.save(row);
        auditLogService.recordAs(CblInvoiceIntakeService.INTAKE_USER, "CBL_EMAIL_NEEDS_ATTENTION", "CblEmail", row.getId(),
                "A CBL " + ("CREDIT_NOTES".equals(kind) ? "credit note file" : "invoice") + (fileName == null ? "" : " (" + clip(fileName, 60) + ")")
                        + " could not be read: " + clip(text.replace('\n', ' '), 250));
    }

    /** The same PDF was on the "needs attention" list and has now been imported (or was already in): close it. */
    private void resolveEarlier(String hash, String note) {
        repository.findFirstByFileHashAndStatusOrderByIdDesc(hash, CblEmailIntake.NEEDS_ATTENTION).ifPresent(row -> {
            if (!row.isResolved()) {
                row.setResolved(true);
                row.setResolvedBy("system");
                row.setResolvedAt(BusinessTime.now());
                row.setResolutionNote(note);
                repository.save(row);
            }
        });
    }

    private CblEmailIntake newRow(String kind, String status, String fileName, String subject, String hash) {
        CblEmailIntake row = new CblEmailIntake();
        row.setKind(kind);
        row.setStatus(status);
        row.setFileName(clip(fileName, 255));
        row.setEmailSubject(clip(subject, 300));
        row.setFileHash(hash);
        return row;
    }

    private static String safeName(String fileName) {
        String base = fileName == null ? "" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") && base.length() > 4 ? base : "CBL-email.pdf";
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String clip(String value, int max) {
        return value == null ? null : value.length() > max ? value.substring(0, max) : value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes));
        } catch (NoSuchAlgorithmException exception) {
            return HexFormat.of().formatHex(String.valueOf(bytes == null ? 0 : bytes.length).getBytes(StandardCharsets.UTF_8));
        }
    }
}
