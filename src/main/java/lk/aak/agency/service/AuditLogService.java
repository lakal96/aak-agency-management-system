package lk.aak.agency.service;

import lk.aak.agency.model.AuditLog;
import lk.aak.agency.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** Records who did what to which record - failures here must never block the action itself. */
    public void record(String action, String entityType, Long entityId, String details) {
        recordAs(currentUsername(), action, entityType, entityId, details);
    }

    /** Same, for work done by the system itself (for example the e-mail invoice intake) rather than a signed-in person. */
    public void recordAs(String username, String action, String entityType, Long entityId, String details) {

        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setUsername(username);
            auditLog.setAction(action);
            auditLog.setEntityType(entityType);
            auditLog.setEntityId(entityId);
            // The column holds 500 characters; a longer text would fail later, at commit, and take the
            // business change down with it - the try/catch below cannot catch that.
            auditLog.setDetails(details != null && details.length() > 500 ? details.substring(0, 500) : details);

            auditLogRepository.save(auditLog);

        } catch (RuntimeException exception) {
            // Audit logging is best-effort - a failure to record history must not fail the
            // underlying business operation (e.g. a delete or approval) that triggered it.
        }
    }

    private String currentUsername() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        return authentication == null ? "SYSTEM" : authentication.getName();
    }

    public Page<AuditLog> getLogPage(String search, int page, int size) {

        return auditLogRepository.search(
                search == null ? "" : search.trim(),
                PageRequest.of(
                        Math.max(page, 0),
                        Math.max(size, 1),
                        Sort.by(Sort.Direction.DESC, "createdAt")
                )
        );
    }
}
