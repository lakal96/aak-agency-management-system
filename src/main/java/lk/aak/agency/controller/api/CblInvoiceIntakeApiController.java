package lk.aak.agency.controller.api;

import lk.aak.agency.intake.CblInvoiceIntakeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
 * as far as the secret token goes: without AAK_INTAKE_TOKEN set on the server the address does not exist, and with
 * a wrong token nothing is read. It can only ever create a DRAFT purchase invoice.
 */
@RestController
@RequestMapping("/api/v1/purchase-invoices")
public class CblInvoiceIntakeApiController {

    private final CblInvoiceIntakeService intakeService;
    private final String token;

    public CblInvoiceIntakeApiController(CblInvoiceIntakeService intakeService, @Value("${app.intake.token:}") String token) {
        this.intakeService = intakeService;
        this.token = token == null ? "" : token.trim();
    }

    @PostMapping("/email-intake")
    public ResponseEntity<CblInvoiceIntakeService.Result> intake(
            @RequestHeader(value = "X-Intake-Token", required = false) String suppliedToken,
            @RequestParam("file") MultipartFile file) throws IOException {

        if (token.length() < 24) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // switched off
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), suppliedToken.trim().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CblInvoiceIntakeService.Result result = intakeService.importInvoice(file.getBytes(), file.getOriginalFilename());
        HttpStatus status = switch (result.status()) {
            case CREATED -> HttpStatus.CREATED;
            case DUPLICATE -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(result);
    }
}
