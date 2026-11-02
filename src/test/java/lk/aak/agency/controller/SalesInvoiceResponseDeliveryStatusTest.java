package lk.aak.agency.controller;

import lk.aak.agency.dto.api.SalesInvoiceResponse;
import lk.aak.agency.model.SalesInvoice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery-trip screen needs each bill's current delivery status; it used to be stored but
 * never sent, so the office could set a status and never see it again.
 */
class SalesInvoiceResponseDeliveryStatusTest {

    @Test
    void responseCarriesTheBillsDeliveryStatus() {

        SalesInvoice invoice = new SalesInvoice();
        invoice.setDeliveryStatus("PARTIAL");

        assertThat(new SalesInvoiceResponse(invoice).getDeliveryStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void aBillNotOnAnyTripHasNoDeliveryStatus() {

        assertThat(new SalesInvoiceResponse(new SalesInvoice()).getDeliveryStatus()).isNull();
    }
}
