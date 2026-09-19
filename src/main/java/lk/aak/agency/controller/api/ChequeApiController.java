package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.ChequeDashboardResponse;
import lk.aak.agency.dto.api.ChequeStatusUpdateRequest;
import lk.aak.agency.dto.api.PaymentResponse;
import lk.aak.agency.model.Payment;
import lk.aak.agency.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/** Mirrors ChequeController's Thymeleaf dashboard logic exactly, just as JSON. */
@RestController
@RequestMapping("/api/v1/cheques")
public class ChequeApiController {

    private static final ZoneId SRI_LANKA_TIME_ZONE = ZoneId.of("Asia/Colombo");

    private final PaymentService paymentService;

    public ChequeApiController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ChequeDashboardResponse dashboard() {

        LocalDate today = LocalDate.now(SRI_LANKA_TIME_ZONE);

        List<Payment> cheques = paymentService.getAllPayments().stream()
                .filter(this::isChequePayment)
                .sorted(Comparator.comparing(Payment::getChequeDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        long overdueCount = cheques.stream().filter(payment -> isOverdueCheque(payment, today)).count();

        return new ChequeDashboardResponse(
                cheques.stream().map(PaymentResponse::new).toList(),
                cheques.size(),
                countByStatus(cheques, "RECEIVED"),
                countByStatus(cheques, "DEPOSITED"),
                countByStatus(cheques, "CLEARED"),
                countByStatus(cheques, "RETURNED"),
                overdueCount,
                sumAmount(cheques, payment -> true),
                sumAmount(cheques, payment -> {
                    String status = getChequeStatus(payment);
                    return "RECEIVED".equals(status) || "DEPOSITED".equals(status);
                }),
                sumAmount(cheques, payment -> "CLEARED".equals(getChequeStatus(payment))),
                sumAmount(cheques, payment -> "RETURNED".equals(getChequeStatus(payment)))
        );
    }

    @PutMapping("/{paymentId}/status")
    public PaymentResponse updateStatus(
            @PathVariable Long paymentId,
            @Valid @RequestBody ChequeStatusUpdateRequest request) {

        Payment updated = paymentService.updateChequeStatus(
                paymentId, request.getChequeStatus(), request.getActionDate(), request.getReturnReason()
        );

        return new PaymentResponse(updated);
    }

    private boolean isChequePayment(Payment payment) {
        return payment != null && "CHEQUE".equalsIgnoreCase(payment.getPaymentMethod());
    }

    private String getChequeStatus(Payment payment) {
        if (payment.getChequeStatus() == null || payment.getChequeStatus().isBlank()) {
            return "RECEIVED";
        }
        return payment.getChequeStatus().trim().toUpperCase();
    }

    private long countByStatus(List<Payment> cheques, String status) {
        return cheques.stream().filter(payment -> status.equals(getChequeStatus(payment))).count();
    }

    private BigDecimal sumAmount(List<Payment> cheques, java.util.function.Predicate<Payment> filter) {
        return cheques.stream()
                .filter(filter)
                .map(payment -> payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isOverdueCheque(Payment payment, LocalDate today) {
        if (payment.getChequeDate() == null) return false;
        String status = getChequeStatus(payment);
        boolean pending = "RECEIVED".equals(status) || "DEPOSITED".equals(status);
        return pending && payment.getChequeDate().isBefore(today);
    }
}
