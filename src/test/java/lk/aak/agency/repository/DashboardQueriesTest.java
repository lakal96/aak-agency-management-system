package lk.aak.agency.repository;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.DeliveryTrip;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The dashboard's SQL, run for real (H2 in MySQL mode): what counts as a sale, and what a rep may see. */
@SpringBootTest
@Transactional
class DashboardQueriesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Long REP = 5L;
    private static final Long OTHER_REP = 6L;

    @Autowired private SalesInvoiceRepository invoices;
    @Autowired private SalesInvoiceItemRepository items;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductRepository products;
    @Autowired private DeliveryTripRepository trips;

    private Customer myShop;
    private Customer otherShop;
    private Product biscuit;
    private Product uncategorised;

    @BeforeEach
    void setUp() {
        myShop = shop("MINE", REP);
        otherShop = shop("OTHER", OTHER_REP);
        biscuit = product("P1", "Biscuits");
        uncategorised = product("P2", null);
    }

    private Customer shop(String code, Long employeeId) {
        Customer customer = new Customer();
        customer.setCustomerCode(code);
        customer.setCustomerName("Shop " + code);
        customer.setAssignedEmployeeId(employeeId);
        return customers.save(customer);
    }

    private Product product(String code, String category) {
        Product product = new Product();
        product.setCblProductCode(code);
        product.setProductName("Product " + code);
        product.setUnit("PCS");
        product.setCategory(category);
        return products.save(product);
    }

    private SalesInvoice invoice(String number, Customer customer, String status, String net, LocalDate date) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber(number);
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(date);
        invoice.setStatus(status);
        invoice.setGrossAmount(new BigDecimal(net));
        invoice.setNetAmount(new BigDecimal(net));
        return invoices.save(invoice);
    }

    private void line(SalesInvoice invoice, Product product, String amount) {
        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setSalesInvoice(invoice);
        item.setProduct(product);
        item.setQuantity(BigDecimal.ONE);
        item.setSalesUnit("PCS");
        item.setUnitPrice(new BigDecimal(amount));
        item.setAmount(new BigDecimal(amount));
        items.save(item);
    }

    @Test
    void draftBillsAreNotSales() {
        invoice("S-1", myShop, "COMPLETED", "1000.00", DAY);
        invoice("S-2", myShop, "DRAFT", "9000.00", DAY);

        assertThat(invoices.sumNetAmountBetween(DAY, DAY)).isEqualByComparingTo("1000");

        List<Object[]> byDay = invoices.completedTotalsByDay(DAY, DAY, null);
        assertThat(byDay).hasSize(1);
        assertThat(((Number) byDay.get(0)[1]).longValue()).isEqualTo(1);
        assertThat((BigDecimal) byDay.get(0)[2]).isEqualByComparingTo("1000");
    }

    @Test
    void aRepOnlySeesTheirOwnShops() {
        invoice("S-1", myShop, "COMPLETED", "1000.00", DAY);
        invoice("S-2", otherShop, "COMPLETED", "5000.00", DAY);

        assertThat((BigDecimal) invoices.completedTotalsByDay(DAY, DAY, null).get(0)[2]).isEqualByComparingTo("6000");
        assertThat((BigDecimal) invoices.completedTotalsByDay(DAY, DAY, REP).get(0)[2]).isEqualByComparingTo("1000");
        assertThat(invoices.completedTotalsByDay(DAY, DAY, 99L)).isEmpty();

        assertThat(invoices.buyingCustomersByDay(DAY, DAY, null)).hasSize(2);
        assertThat(invoices.buyingCustomersByDay(DAY, DAY, REP)).hasSize(1);
    }

    @Test
    void aShopThatBoughtTwiceInADayIsListedOnce() {
        invoice("S-1", myShop, "COMPLETED", "100.00", DAY);
        invoice("S-2", myShop, "COMPLETED", "200.00", DAY);

        assertThat(invoices.buyingCustomersByDay(DAY, DAY, null)).hasSize(1);
    }

    @Test
    void categoriesGroupUncategorisedProductsTogetherAndSkipDraftsAndOtherShopsForReps() {
        SalesInvoice mine = invoice("S-1", myShop, "COMPLETED", "300.00", DAY);
        line(mine, biscuit, "200.00");
        line(mine, uncategorised, "100.00");
        SalesInvoice draft = invoice("S-2", myShop, "DRAFT", "999.00", DAY);
        line(draft, biscuit, "999.00");
        SalesInvoice others = invoice("S-3", otherShop, "COMPLETED", "50.00", DAY);
        line(others, biscuit, "50.00");

        Map<String, BigDecimal> everyone = toMap(items.completedSalesByCategory(DAY, DAY, null));
        assertThat(everyone.get("Biscuits")).isEqualByComparingTo("250");
        assertThat(everyone.get("Uncategorised")).isEqualByComparingTo("100");

        Map<String, BigDecimal> rep = toMap(items.completedSalesByCategory(DAY, DAY, REP));
        assertThat(rep.get("Biscuits")).isEqualByComparingTo("200");
    }

    @Test
    void deliveryCountsAreForTripsOnThatDayOnly() {
        DeliveryTrip today = trip(DAY, "LOADED");
        DeliveryTrip yesterday = trip(DAY.minusDays(1), "LOADED");

        SalesInvoice a = invoice("S-1", myShop, "COMPLETED", "10.00", DAY);
        a.setDeliveryTripId(today.getId());
        a.setDeliveryStatus("PENDING");
        invoices.save(a);
        SalesInvoice b = invoice("S-2", otherShop, "COMPLETED", "10.00", DAY);
        b.setDeliveryTripId(today.getId());
        b.setDeliveryStatus("COMPLETED");
        invoices.save(b);
        SalesInvoice c = invoice("S-3", myShop, "COMPLETED", "10.00", DAY);
        c.setDeliveryTripId(yesterday.getId());
        c.setDeliveryStatus("PENDING");
        invoices.save(c);

        long all = invoices.deliveryCountsForDay(DAY, null).stream().mapToLong(row -> ((Number) row[2]).longValue()).sum();
        long mine = invoices.deliveryCountsForDay(DAY, REP).stream().mapToLong(row -> ((Number) row[2]).longValue()).sum();
        assertThat(all).isEqualTo(2);
        assertThat(mine).isEqualTo(1);
    }

    private DeliveryTrip trip(LocalDate date, String status) {
        DeliveryTrip trip = new DeliveryTrip();
        trip.setTripDate(date);
        trip.setVehicleId(1L);
        trip.setStatus(status);
        return trips.save(trip);
    }

    private static Map<String, BigDecimal> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (String) row[0], row -> (BigDecimal) row[1], BigDecimal::add));
    }
}
