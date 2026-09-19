package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.PaymentRequest;
import lk.aak.agency.dto.api.PaymentResponse;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.service.PaymentService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PaymentService paymentService;

    public PaymentApiController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public PageResponse<PaymentResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<Payment> payments = paymentService.getPaymentPage(q, page, size);
        return PageResponse.from(payments, PaymentResponse::new);
    }

    @GetMapping("/{id}")
    public PaymentResponse getById(@PathVariable Long id) {
        return new PaymentResponse(paymentService.getPaymentById(id));
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request) {

        Payment payment = new Payment();
        payment.setReceiptNumber(request.getReceiptNumber());

        SalesInvoice invoiceRef = new SalesInvoice();
        invoiceRef.setId(request.getSalesInvoiceId());
        payment.setSalesInvoice(invoiceRef);

        payment.setPaymentDate(request.getPaymentDate());
        payment.setAmount(request.getAmount());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setChequeNumber(request.getChequeNumber());
        payment.setChequeDate(request.getChequeDate());
        payment.setBankName(request.getBankName());
        payment.setReferenceNumber(request.getReferenceNumber());
        payment.setNotes(request.getNotes());

        Payment saved = paymentService.savePayment(payment);

        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentResponse(saved));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }
}
