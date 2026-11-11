package lk.aak.agency.controller.api;

import lk.aak.agency.intake.CblEmailIntake;
import lk.aak.agency.intake.CblEmailIntakeLog;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.dto.api.PageResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/** The CBL e-mail box for office staff and the owner: what came in by e-mail, and what needs a person. */
@RestController
@RequestMapping("/api/v1/cbl-email-intake")
@PreAuthorize("hasAnyRole('ADMIN','OFFICE')")
public class CblEmailInboxApiController {

    private final CblEmailIntakeLog log;
    private final PurchaseInvoiceFileService fileService;

    public CblEmailInboxApiController(CblEmailIntakeLog log, PurchaseInvoiceFileService fileService) {
        this.log = log;
        this.fileService = fileService;
    }

    public record Row(Long id, LocalDateTime receivedAt, LocalDateTime lastSeenAt, int attempts, String kind, String status,
                      String fileName, Long fileSize, String emailSubject, String emailSender, LocalDateTime emailSentAt,
                      String documentNumber, String summary, List<String> problems,
                      Long purchaseInvoiceId, boolean hasFile, boolean resolved, String resolvedBy, LocalDateTime resolvedAt,
                      String resolutionNote) {
        static Row of(CblEmailIntake e) {
            return new Row(e.getId(), e.getReceivedAt(), e.getLastSeenAt(), e.getAttempts(), e.getKind(), e.getStatus(), e.getFileName(),
                    e.getFileSize(), e.getEmailSubject(), e.getEmailSender(), e.getEmailSentAt(), e.getDocumentNumber(), e.getSummary(),
                    e.getProblems() == null || e.getProblems().isBlank() ? List.of() : List.of(e.getProblems().split("\\R")),
                    e.getPurchaseInvoiceId(), e.getStoredFileName() != null, e.isResolved(), e.getResolvedBy(), e.getResolvedAt(), e.getResolutionNote());
        }
    }

    public record ResolveRequest(String note) {
    }

    /** `status=attention` (default) = only what needs a person; `status=all` = the latest records of everything. */
    @GetMapping
    public List<Row> list(@RequestParam(defaultValue = "attention") String status, @RequestParam(defaultValue = "30") int limit) {
        return log.list("attention".equalsIgnoreCase(status), limit).stream().map(Row::of).toList();
    }

    /** The whole CBL e-mail list, a page at a time. filter = all | attention | imported | handled | empty; q searches subject, file name, document number, sender. */
    @GetMapping("/page")
    public PageResponse<Row> page(@RequestParam(defaultValue = "all") String filter, @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(log.search(filter, q, page, size), Row::of);
    }

    @GetMapping("/counts")
    public CblEmailIntakeLog.Counts counts() {
        return log.counts();
    }

    @GetMapping("/{id}")
    public Row one(@PathVariable Long id) {
        return Row.of(log.get(id));
    }

    @GetMapping("/summary")
    public CblEmailIntakeLog.Summary summary() {
        return log.summary();
    }

    @PostMapping("/{id}/resolve")
    public Row resolve(@PathVariable Long id, @RequestBody(required = false) ResolveRequest request, Authentication authentication) {
        return Row.of(log.resolve(id, authentication.getName(), request == null ? null : request.note()));
    }

    /** The PDF CBL sent (kept for everything that arrived): shown in the browser, or saved with `download=true`. */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download) {
        CblEmailIntake row = log.get(id);
        if (row.getStoredFileName() == null) throw new IllegalArgumentException("No PDF was kept for this record.");
        Resource resource = fileService.loadFile(row.getStoredFileName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                        .filename(row.getFileName() == null ? "CBL-email.pdf" : row.getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }
}
