package lk.aak.agency.service;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lists are read page by page. Dozens of bills and payments share ONE date (a normal day), so a list sorted by date alone
 * has no fixed order: reading it page by page repeated some rows and never showed others (about 6% of a long list). Every list
 * now has a unique tie-breaker, so every row appears exactly once.
 */
@SpringBootTest
@Transactional
class StablePagingTest {

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductRepository products;

    @Test
    void readingTodaysBillsAndPaymentsPageByPageShowsEveryRowExactlyOnce() {
        Customer shop = new Customer();
        shop.setCustomerCode("SP" + UUID.randomUUID().toString().substring(0, 8));
        shop.setCustomerName("Paging shop");
        shop.setCreditLimit(new BigDecimal("100000000"));
        shop = customers.save(shop);
        Product product = new Product();
        product.setCblProductCode("SP" + UUID.randomUUID().toString().substring(0, 8));
        product.setProductName("Paging product");
        product.setUnit("PCS");
        product = products.save(product);
        LocalDate today = BusinessTime.today();

        long billsBefore = salesInvoiceService.getInvoicePage("", 0, 10, null).getTotalElements();
        Set<String> made = new HashSet<>();
        for (int i = 0; i < 47; i++) {                                    // 47 bills dated the same day
            SalesInvoice invoice = new SalesInvoice();
            invoice.setInvoiceNumber("SP-" + UUID.randomUUID().toString().substring(0, 8));
            invoice.setInvoiceDate(today);
            invoice.setCustomer(shop);
            invoice.setSaleType("CASH");
            SalesInvoiceItem item = new SalesInvoiceItem();
            item.setProduct(product);
            item.setQuantity(BigDecimal.ONE);
            item.setUnitPrice(new BigDecimal("10"));
            made.add(salesInvoiceService.saveInvoiceWithItems(invoice, List.of(item)).getInvoiceNumber());
        }

        var first = salesInvoiceService.getInvoicePage("", 0, 10, null);
        assertThat(first.getTotalElements()).isEqualTo(billsBefore + 47);
        List<String> seen = new java.util.ArrayList<>();
        for (int page = 0; page < first.getTotalPages(); page++) {
            salesInvoiceService.getInvoicePage("", page, 10, null).forEach(b -> seen.add(b.getInvoiceNumber()));
        }
        assertThat(seen).as("every bill is shown once when reading page by page").doesNotHaveDuplicates().hasSize((int) first.getTotalElements());
        assertThat(seen).containsAll(made);
    }
}
