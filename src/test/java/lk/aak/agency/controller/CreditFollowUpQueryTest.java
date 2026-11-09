package lk.aak.agency.controller;

import lk.aak.agency.controller.api.CustomerApiController;
import lk.aak.agency.dto.api.CreditFollowUpResponse;
import lk.aak.agency.dto.api.CreditFollowUpRowResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.util.BusinessTime;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credit follow-up: which shops owe, how much, how much is overdue - for every kind of bill - and that it answers
 * with a handful of SQL statements however many shops there are (it used to ask once per shop and once per bill).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class CreditFollowUpQueryTest {

    private static final LocalDate TODAY = BusinessTime.today();
    private static final Authentication OFFICE =
            new UsernamePasswordAuthenticationToken("office", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OFFICE")));

    @Autowired private CustomerApiController api;
    @Autowired private CustomerRepository customers;
    @Autowired private SalesInvoiceRepository invoices;
    @Autowired private PaymentRepository payments;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private int counter;

    private Customer shop(String name) {
        Customer customer = new Customer();
        customer.setCustomerCode("FU-" + (++counter));
        customer.setCustomerName(name);
        customer.setStatus("ACTIVE");
        return customers.save(customer);
    }

    private SalesInvoice bill(Customer shop, String status, String saleType, String paymentStatus, String net, LocalDate due) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("FU-INV-" + (++counter));
        invoice.setCustomer(shop);
        invoice.setInvoiceDate(TODAY.minusDays(30));
        invoice.setDueDate(due);
        invoice.setStatus(status);
        invoice.setSaleType(saleType);
        invoice.setPaymentStatus(paymentStatus);
        invoice.setGrossAmount(new BigDecimal(net));
        invoice.setNetAmount(new BigDecimal(net));
        return invoices.save(invoice);
    }

    private void pay(SalesInvoice invoice, String amount, String status) {
        Payment payment = new Payment();
        payment.setReceiptNumber("FU-R-" + (++counter));
        payment.setSalesInvoice(invoice);
        payment.setPaymentDate(TODAY);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod("CASH");
        payment.setStatus(status);
        payments.save(payment);
    }

    private static CreditFollowUpRowResponse rowOf(CreditFollowUpResponse response, Customer shop) {
        return response.getRows().stream().filter(r -> r.getCustomerId().equals(shop.getId())).findFirst().orElse(null);
    }

    @Test
    void everyKindOfBillIsCountedTheWayItAlwaysWas() {
        Customer a = shop("A owes and is late");
        bill(a, "COMPLETED", "CREDIT", "UNPAID", "1000", TODAY.minusDays(10));              // overdue, nothing paid
        SalesInvoice partly = bill(a, "COMPLETED", "CREDIT", "PARTIALLY_PAID", "500", TODAY.plusDays(5));
        pay(partly, "200", "RECEIVED");                                                      // 300 left, not due yet
        Customer b = shop("B paid in full");
        SalesInvoice settled = bill(b, "COMPLETED", "CREDIT", "PAID", "800", TODAY.minusDays(3));
        pay(settled, "800", "RECEIVED");
        Customer c = shop("C cash bill not yet paid");
        bill(c, "COMPLETED", "CASH", "UNPAID", "700", TODAY.minusDays(1));
        Customer d = shop("D old cash bill settled with no payment record");
        bill(d, "COMPLETED", "CASH", "PAID", "900", null);
        Customer e = shop("E only a draft");
        bill(e, "DRAFT", "CREDIT", "UNPAID", "400", TODAY.minusDays(9));
        Customer f = shop("F cheque came back");
        SalesInvoice bounced = bill(f, "COMPLETED", "CREDIT", "UNPAID", "600", TODAY.minusDays(2));
        pay(bounced, "600", "RETURNED");                                                     // a returned cheque is not money
        shop("G no bills");

        CreditFollowUpResponse response = api.creditFollowUp(OFFICE);

        CreditFollowUpRowResponse rowA = rowOf(response, a);
        assertThat(rowA.getTotalOutstanding()).isEqualByComparingTo("1300");
        assertThat(rowA.getOverdueAmount()).isEqualByComparingTo("1000");
        assertThat(rowA.getOldestOverdueDueDate()).isEqualTo(TODAY.minusDays(10));
        assertThat(rowA.getDaysOverdue()).isEqualTo(10);
        assertThat(rowOf(response, b)).isNull();
        assertThat(rowOf(response, c).getTotalOutstanding()).isEqualByComparingTo("700");
        assertThat(rowOf(response, c).getOverdueAmount()).isEqualByComparingTo("700");
        assertThat(rowOf(response, d)).isNull();
        assertThat(rowOf(response, e)).isNull();
        assertThat(rowOf(response, f).getTotalOutstanding()).isEqualByComparingTo("600");
        assertThat(response.getRows()).extracting(CreditFollowUpRowResponse::getCustomerName)
                .doesNotContain("G no bills");
        assertThat(response.getTotalOutstanding()).isEqualByComparingTo("2600");   // 1300 + 700 + 600
        assertThat(response.getTotalOverdue()).isEqualByComparingTo("2300");       // 1000 + 700 + 600
        // the longest-overdue shop is listed first
        assertThat(response.getRows().get(0).getCustomerId()).isEqualTo(a.getId());
    }

    @Test
    void aBigAgencyIsStillAHandfulOfSqlStatements() {
        for (int i = 0; i < 300; i++) {
            Customer shop = shop("Shop " + i);
            bill(shop, "COMPLETED", "CREDIT", "UNPAID", "1000", TODAY.minusDays(5));
            SalesInvoice partly = bill(shop, "COMPLETED", "CREDIT", "PARTIALLY_PAID", "500", TODAY.plusDays(5));
            pay(partly, "100", "RECEIVED");
        }
        SessionFactory sessions = entityManagerFactory.unwrap(SessionFactory.class);
        customers.flush();
        sessions.getStatistics().clear();

        CreditFollowUpResponse response = api.creditFollowUp(OFFICE);

        assertThat(response.getRows()).hasSize(300);
        assertThat(response.getTotalOutstanding()).isEqualByComparingTo("420000");   // 300 shops x (1000 + 400 left on the part-paid bill)
        assertThat(sessions.getStatistics().getPrepareStatementCount())
                .as("SQL statements for 300 shops and 600 bills").isLessThanOrEqualTo(8);
    }
}
