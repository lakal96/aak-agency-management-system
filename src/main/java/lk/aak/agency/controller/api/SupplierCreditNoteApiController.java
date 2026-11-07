package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lk.aak.agency.credit.SupplierCreditNote;
import lk.aak.agency.credit.SupplierCreditNoteService;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Credit notes from the supplier (owner and office): a draft is approved by a person before it reduces what is owed. */
@RestController
@RequestMapping("/api/v1/supplier-credit-notes")
public class SupplierCreditNoteApiController {

    private final SupplierCreditNoteService service;
    private final PurchaseInvoiceFileService fileService;

    public SupplierCreditNoteApiController(SupplierCreditNoteService service, PurchaseInvoiceFileService fileService) {
        this.service = service;
        this.fileService = fileService;
    }

    public static class CreateRequest {
        @NotBlank(message = "Enter the credit note number.")
        public String documentNumber;
        public String issuerName;
        @NotNull(message = "Enter the date of the credit note.")
        public LocalDate noteDate;
        @NotNull(message = "Enter the amount.")
        @DecimalMin(value = "0.01", message = "The amount must be more than 0.")
        @DecimalMax(value = "999999999.99", message = "The amount is too large - please check the number.")
        public BigDecimal amount;
        public String remarks;
    }

    public record Response(Long id, String documentNumber, String issuerName, LocalDate noteDate, String refInvoiceNumber,
                           LocalDate refInvoiceDate, BigDecimal amount, String remarks, String status, String source,
                           boolean hasFile, String fileOriginalName, LocalDateTime createdAt, String approvedBy,
                           LocalDateTime approvedAt) {
        static Response of(SupplierCreditNote n) {
            return new Response(n.getId(), n.getDocumentNumber(), n.getIssuerName(), n.getNoteDate(), n.getRefInvoiceNumber(),
                    n.getRefInvoiceDate(), n.getAmount(), n.getRemarks(), n.getStatus(), n.getSource(), n.hasFile(),
                    n.getFileOriginalName(), n.getCreatedAt(), n.getApprovedBy(), n.getApprovedAt());
        }
    }

    @GetMapping
    public List<Response> list() {
        return service.list().stream().map(Response::of).toList();
    }

    @PostMapping
    public ResponseEntity<Response> create(@Valid @RequestBody CreateRequest request, Principal principal) {
        SupplierCreditNote note = service.create(request.documentNumber, request.issuerName, request.noteDate, request.amount,
                request.remarks, principal == null ? "unknown" : principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.of(note));
    }

    @PostMapping("/{id}/approve")
    public Response approve(@PathVariable Long id, Principal principal) {
        return Response.of(service.approve(id, principal == null ? "unknown" : principal.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long id) {
        SupplierCreditNote note = service.get(id);
        if (!note.hasFile()) throw new IllegalArgumentException("No original file is attached to this credit note.");
        Resource resource = fileService.loadFile(note.getFileStoredName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(note.getFileOriginalName(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }
}
