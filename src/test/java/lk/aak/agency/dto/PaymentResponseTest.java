package lk.aak.agency.dto;

import lk.aak.agency.dto.api.PaymentResponse;
import lk.aak.agency.model.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** A bank transfer's transaction number is saved with the payment, so it must come back to the screen. */
class PaymentResponseTest {

    @Test
    void aBankTransfersReferenceNumberAndBankComeBackWithThePayment() {
        Payment payment = new Payment();
        payment.setReceiptNumber("R-1");
        payment.setPaymentMethod("BANK_TRANSFER");
        payment.setAmount(new BigDecimal("9604.00"));
        payment.setReferenceNumber("TRF-88213");
        payment.setBankName("Sampath Bank");

        PaymentResponse response = new PaymentResponse(payment);

        assertThat(response.getReferenceNumber()).isEqualTo("TRF-88213");
        assertThat(response.getBankName()).isEqualTo("Sampath Bank");
    }
}
