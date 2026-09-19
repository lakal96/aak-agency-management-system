package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.AuditLogResponse;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.model.AuditLog;
import lk.aak.agency.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only - enforced both here and at the security-filter level (matches the Thymeleaf app's rule). */
@RestController
@RequestMapping("/api/v1/audit-log")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AuditLogRepository auditLogRepository;

    public AuditLogApiController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public PageResponse<AuditLogResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<AuditLog> logs = auditLogRepository.search(
                q == null ? "" : q,
                PageRequest.of(Math.max(page, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "id"))
        );

        return PageResponse.from(logs, AuditLogResponse::new);
    }
}
