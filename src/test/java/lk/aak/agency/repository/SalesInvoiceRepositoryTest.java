package lk.aak.agency.repository;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SalesInvoiceRepositoryTest {

    @Autowired
    private SalesInvoiceRepository salesInvoiceRepository;

    @Autowired
    private CustomerRepository customerRepository;

    private SalesInvoice saveInvoice(String invoiceNumber, Customer customer) {

        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(BusinessTime.today());
        invoice.setSaleType("CREDIT");
        invoice.setStatus("DRAFT");
        invoice.setGrossAmount(BigDecimal.TEN);
        invoice.setNetAmount(BigDecimal.TEN);

        return salesInvoiceRepository.save(invoice);
    }

    private Customer saveCustomer(String name, String area) {

        Customer customer = new Customer();
        customer.setCustomerCode("CUST-" + name);
        customer.setCustomerName(name);
        customer.setArea(area);

        return customerRepository.save(customer);
    }

    @Test
    void search_withBlankKeyword_returnsEverythingPaged() {

        Customer customer = saveCustomer("Colombo Mart", "Colombo");
        saveInvoice("INV-001", customer);
        saveInvoice("INV-002", customer);

        Page<SalesInvoice> result = salesInvoiceRepository.search(
                "", null, PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "invoiceNumber"))
        );

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void search_matchesByInvoiceNumber() {

        Customer customer = saveCustomer("Kandy Shop", "Kandy");
        saveInvoice("INV-100", customer);
        saveInvoice("INV-200", customer);

        Page<SalesInvoice> result = salesInvoiceRepository.search(
                "inv-100", null, PageRequest.of(0, 10)
        );

        assertThat(result.getContent())
                .extracting(SalesInvoice::getInvoiceNumber)
                .containsExactly("INV-100");
    }

    @Test
    void search_matchesByCustomerNameOrArea_caseInsensitive() {

        Customer galle = saveCustomer("Galle Supermarket", "Galle");
        Customer matara = saveCustomer("Matara Traders", "Matara");
        saveInvoice("INV-301", galle);
        saveInvoice("INV-302", matara);

        Page<SalesInvoice> byName = salesInvoiceRepository.search(
                "galle", null, PageRequest.of(0, 10)
        );
        Page<SalesInvoice> byArea = salesInvoiceRepository.search(
                "MATARA", null, PageRequest.of(0, 10)
        );

        assertThat(byName.getContent())
                .extracting(SalesInvoice::getInvoiceNumber)
                .containsExactly("INV-301");
        assertThat(byArea.getContent())
                .extracting(SalesInvoice::getInvoiceNumber)
                .containsExactly("INV-302");
    }

    @Test
    void search_withNoMatch_returnsEmptyPage() {

        Customer customer = saveCustomer("Negombo Shop", "Negombo");
        saveInvoice("INV-500", customer);

        Page<SalesInvoice> result = salesInvoiceRepository.search(
                "nonexistent-term", null, PageRequest.of(0, 10)
        );

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void search_withEmployeeId_onlyReturnsInvoicesForThatEmployeesCustomers() {

        Customer assigned = saveCustomer("Assigned Shop", "Colombo");
        assigned.setAssignedEmployeeId(42L);
        customerRepository.save(assigned);

        Customer unassigned = saveCustomer("Other Shop", "Colombo");
        saveInvoice("INV-700", assigned);
        saveInvoice("INV-701", unassigned);

        Page<SalesInvoice> scoped = salesInvoiceRepository.search("", 42L, PageRequest.of(0, 10));

        assertThat(scoped.getContent())
                .extracting(SalesInvoice::getInvoiceNumber)
                .containsExactly("INV-700");
    }

    @Test
    void aBillSavedWithoutADateGetsSriLankasDateEvenWhenUtcIsStillYesterday() {

        // 00:30 on 15 January 2030 in Colombo = 19:00 on 14 January in UTC (a date far from the real one, so the frozen clock is what is being tested).
        BusinessTime.setClockForTesting(java.time.Clock.fixed(java.time.Instant.parse("2030-01-14T19:00:00Z"), java.time.ZoneOffset.UTC));
        try {
            SalesInvoice invoice = new SalesInvoice();
            invoice.setInvoiceNumber("INV-NODATE");
            invoice.setCustomer(saveCustomer("Night Owl Mart", "Colombo"));
            invoice.setStatus("DRAFT");
            invoice.setGrossAmount(BigDecimal.TEN);
            invoice.setNetAmount(BigDecimal.TEN);

            SalesInvoice saved = salesInvoiceRepository.saveAndFlush(invoice);

            assertThat(saved.getInvoiceDate()).isEqualTo(LocalDate.of(2030, 1, 15));
            assertThat(saved.getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2030, 1, 15, 0, 30));
        } finally {
            BusinessTime.resetClock();
        }
    }
}
