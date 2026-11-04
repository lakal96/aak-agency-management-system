package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.CollectionSummaryResponse;
import lk.aak.agency.dto.api.PaymentResponse;
import lk.aak.agency.model.Payment;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.service.PaymentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** Mirrors CollectionReportController - date range accepted directly instead of a period enum. */
@RestController
@RequestMapping("/api/v1/collections")
public class CollectionApiController {

    private static final ZoneId SRI_LANKA_TIME_ZONE = ZoneId.of("Asia/Colombo");

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public CollectionApiController(PaymentRepository paymentRepository, PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    @GetMapping("/summary")
    public CollectionSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        LocalDate today = LocalDate.now(SRI_LANKA_TIME_ZONE);
        LocalDate resolvedFrom = fromDate != null ? fromDate : today.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate resolvedTo = toDate != null ? toDate : today;

        List<Payment> payments = paymentRepository.findByPaymentDateBetweenOrderByPaymentDateDesc(resolvedFrom, resolvedTo);

        // Rules (same as the dashboard): a cheque is collected once it CLEARS; bounced (RETURNED) cheques
        // are never counted; bank transfers are collected but are not cash in hand; credit notes from
        // shop returns are not collections at all (see PaymentService.summariseCollections).
        PaymentService.CollectionTotals totals = paymentService.summariseCollections(payments);

        return new CollectionSummaryResponse(
                resolvedFrom,
                resolvedTo,
                totals.totalCollected(),
                totals.totalCash(),
                totals.totalCheque(),
                totals.totalBankTransfer(),
                totals.cashCount(),
                totals.chequeCount(),
                totals.bankTransferCount(),
                payments.stream()
                        .filter(payment -> !PaymentService.CREDIT_NOTE.equalsIgnoreCase(payment.getPaymentMethod()))
                        .map(PaymentResponse::new)
                        .toList(),
                totals.chequeClearedTotal(),
                totals.chequeNotYetClearedTotal(),
                totals.chequeReturnedTotal()
        );
    }

    @GetMapping("/handover")
    public List<PaymentService.HandoverSummaryRow> handover(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate selectedDate = date != null ? date : LocalDate.now(SRI_LANKA_TIME_ZONE);
        return paymentService.getHandoverSummaryForDate(selectedDate);
    }

    @PostMapping("/handover/mark")
    public void markHandedOver(
            @RequestParam Long collectorEmployeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        paymentService.markHandedOver(collectorEmployeeId, date);
    }
}
