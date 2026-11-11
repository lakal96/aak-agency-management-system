package lk.aak.agency.controller.api;

import lk.aak.agency.intake.CblEmailIntakeLog;
import lk.aak.agency.intake.CblInvoiceIntakeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Where the Gmail script (see handbook/gmail-intake) posts the PDF CBL e-mailed. It is open to the internet only
 * as far as the secret token goes: without AAK_INTAKE_TOKEN set on the server the addresses do not exist, and
 * with a wrong token nothing is read. It can only ever create a DRAFT purchase invoice (or draft credit notes), and
 * leave a record that the e-mail arrived, so a person can see in the system what was imported and what needs attention.
 */
@RestController
@RequestMapping("/api/v1/purchase-invoices")
public class CblInvoiceIntakeApiController {

    private final CblInvoiceIntakeService intakeService;
    private final CblEmailIntakeLog emailLog;
    private final String token;

    public CblInvoiceIntakeApiController(CblInvoiceIntakeService intakeService, CblEmailIntakeLog emailLog,
                                         @Value("${app.intake.token:}") String token) {
        this.intakeService = intakeService;
        this.emailLog = emailLog;
        this.token = token == null ? "" : token.trim();
    }

    /** What the script says about an e-mail from CBL that it could not use at all. */
    public record UnreadReport(String messageKey, String subject, String reason, String sender, String sentAt) {
    }

    /** The script sends the e-mail's own date as an ISO timestamp (with or without an offset); anything unreadable is simply left out. */
    static java.time.LocalDateTime parseSentAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value.trim()).atZoneSameInstant(lk.aak.agency.util.BusinessTime.ZONE).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException notOffset) {
            try {
                return java.time.LocalDateTime.parse(value.trim());
            } catch (java.time.format.DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    /** 404 while the e-mail intake is switched off (no token on the server), 401 for a wrong token, null when the caller may go on. */
    private HttpStatus refuse(String suppliedToken) {
        if (token.length() < 24) {
            return HttpStatus.NOT_FOUND; // switched off
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), suppliedToken.trim().getBytes(StandardCharsets.UTF_8))) {
            return HttpStatus.UNAUTHORIZED;
        }
        return null;
    }

    @PostMapping("/email-intake")
    public ResponseEntity<CblInvoiceIntakeService.Result> intake(
            @RequestHeader(value = "X-Intake-Token", required = false) String suppliedToken,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "sender", required = false) String sender,
            @RequestParam(value = "sentAt", required = false) String sentAt) throws IOException {

        HttpStatus refused = refuse(suppliedToken);
        if (refused != null) {
            return ResponseEntity.status(refused).build();
        }

        byte[] bytes = file.getBytes();
        CblInvoiceIntakeService.Result result = intakeService.importInvoice(bytes, file.getOriginalFilename());
        emailLog.recordOutcome(result, bytes, file.getOriginalFilename(), new CblEmailIntakeLog.Mail(subject, sender, parseSentAt(sentAt)));
        HttpStatus status = switch (result.status()) {
            case CREATED -> HttpStatus.CREATED;
            case DUPLICATE, EMPTY -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(result);
    }

    /** The script found an e-mail from CBL it cannot use (no PDF attached, a subject it does not know...): it goes on the "needs attention" list once. */
    @PostMapping("/email-intake/unread")
    public ResponseEntity<Void> unread(@RequestHeader(value = "X-Intake-Token", required = false) String suppliedToken,
                                       @RequestBody UnreadReport report) {
        HttpStatus refused = refuse(suppliedToken);
        if (refused != null) {
            return ResponseEntity.status(refused).build();
        }
        emailLog.recordUnread(report.messageKey(), new CblEmailIntakeLog.Mail(report.subject(), report.sender(), parseSentAt(report.sentAt())), report.reason());
        return ResponseEntity.noContent().build();
    }

    /** The script reports in at every run, so the system can tell when it has stopped. */
    @PostMapping("/email-intake/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestHeader(value = "X-Intake-Token", required = false) String suppliedToken,
                                          @RequestBody(required = false) java.util.Map<String, String> body) {
        HttpStatus refused = refuse(suppliedToken);
        if (refused != null) {
            return ResponseEntity.status(refused).build();
        }
        emailLog.heartbeat(body == null ? null : body.get("detail"));
        return ResponseEntity.noContent().build();
    }
}
