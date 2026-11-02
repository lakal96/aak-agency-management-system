package lk.aak.agency.controller;

import lk.aak.agency.controller.api.PaymentApiController;
import lk.aak.agency.dto.api.PaymentRequest;
import lk.aak.agency.dto.api.PaymentResponse;
import lk.aak.agency.model.Payment;
import lk.aak.agency.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The React app records payments through the REST API. The "collected by" employee must reach the
 * service, otherwise a payment taken in the field never shows up in the daily handover
 * reconciliation (the office could not tell what a driver or collector still owed).
 */
@ExtendWith(MockitoExtension.class)
class PaymentApiCollectorTest {

    @Mock
    private PaymentService paymentService;

    private PaymentRequest request(Long collectorId) {
        PaymentRequest request = new PaymentRequest();
        request.setReceiptNumber("REC-1");
        request.setSalesInvoiceId(10L);
        request.setPaymentDate(LocalDate.of(2026, 11, 2));
        request.setAmount(new BigDecimal("2500"));
        request.setPaymentMethod("CASH");
        request.setCollectedByEmployeeId(collectorId);
        return request;
    }

    @Test
    void create_passesTheCollectorOnToTheService() {

        PaymentApiController controller = new PaymentApiController(paymentService);
        when(paymentService.savePayment(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<PaymentResponse> response = controller.create(request(7L));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentService).savePayment(saved.capture());
        assertThat(saved.getValue().getCollectedByEmployeeId()).isEqualTo(7L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getCollectedByEmployeeId()).isEqualTo(7L);
    }

    @Test
    void create_withoutACollectorMeansTheOfficeTookTheMoneyDirectly() {

        PaymentApiController controller = new PaymentApiController(paymentService);
        when(paymentService.savePayment(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<PaymentResponse> response = controller.create(request(null));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentService).savePayment(saved.capture());
        assertThat(saved.getValue().getCollectedByEmployeeId()).isNull();
        assertThat(response.getBody().getCollectedByEmployeeId()).isNull();
    }

    @Test
    void response_exposesWhoCollectedItAndTheHandoverStatus() {

        Payment payment = new Payment();
        payment.setCollectedByEmployeeId(7L);
        payment.setCollectedByName("Ruwan Jayasinghe");
        payment.setHandoverStatus("PENDING");

        PaymentResponse response = new PaymentResponse(payment);

        assertThat(response.getCollectedByName()).isEqualTo("Ruwan Jayasinghe");
        assertThat(response.getHandoverStatus()).isEqualTo("PENDING");
    }
}
