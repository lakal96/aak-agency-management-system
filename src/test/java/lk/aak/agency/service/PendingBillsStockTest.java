package lk.aak.agency.service;

import lk.aak.agency.controller.api.InventoryApiController;
import lk.aak.agency.dto.api.InventoryItemResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two reps each write a bill for all 10 pieces in stock. Nothing is taken until a bill is completed, so both drafts
 * can be saved and only the first Complete succeeds - but the bill screen is told that other pending bills already
 * ask for the stock, so the second rep is warned while ordering.
 */
@SpringBootTest
@Transactional
class PendingBillsStockTest {

    @Autowired private InventoryApiController inventory;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductRepository products;
    @Autowired private StockMovementRepository stockMovements;

    private int sequence;

    private Product productWithStock(String stock) {
        Product p = new Product();
        p.setCblProductCode("PB" + UUID.randomUUID().toString().substring(0, 8));
        p.setProductName("Pending test product");
        p.setUnit("PCS");
        p = products.save(p);
        StockMovement movement = new StockMovement();
        movement.setProduct(p);
        movement.setMovementType("OPENING_STOCK");
        movement.setQuantityChange(new BigDecimal(stock));
        movement.setStockUnit("PCS");
        movement.setReferenceType("TEST_OPENING");
        movement.setReferenceItemId(System.nanoTime());
        stockMovements.save(movement);
        return p;
    }

    private Customer shop() {
        Customer c = new Customer();
        c.setCustomerCode("PB" + UUID.randomUUID().toString().substring(0, 8));
        c.setCustomerName("Pending shop");
        c.setCreditLimit(new BigDecimal("1000000"));
        return customers.save(c);
    }

    private SalesInvoice draft(Product p, int quantity, LocalDate date) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("PB-" + UUID.randomUUID().toString().substring(0, 8) + (++sequence));
        invoice.setInvoiceDate(date);
        invoice.setCustomer(shop());
        invoice.setSaleType("CREDIT");
        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setProduct(p);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal("100"));
        return salesInvoiceService.saveInvoiceWithItems(invoice, List.of(item));
    }

    private InventoryItemResponse row(Product p, Long excludeInvoiceId) {
        return inventory.availability(excludeInvoiceId).stream().filter(i -> i.getProductId().equals(p.getId())).findFirst().orElseThrow();
    }

    @Test
    void theBillScreenIsToldHowMuchOtherPendingBillsAlreadyAskFor() {
        Product p = productWithStock("10");
        SalesInvoice repA = draft(p, 10, BusinessTime.today());
        SalesInvoice repB = draft(p, 10, BusinessTime.today());

        // nobody has taken anything: both drafts were saved, the stock still reads 10
        assertThat(row(p, null).getCurrentStock()).isEqualByComparingTo("10");
        assertThat(row(p, null).getReservedByDrafts()).isEqualByComparingTo("20");
        assertThat(row(p, null).getPendingBills()).isEqualTo(2);

        // a rep editing his own bill is told about the OTHER bill only
        assertThat(row(p, repA.getId()).getReservedByDrafts()).isEqualByComparingTo("10");
        assertThat(row(p, repA.getId()).getPendingBills()).isEqualTo(1);

        // the first Complete takes the stock; the second is refused
        salesInvoiceService.completeInvoice(repA.getId());
        assertThat(row(p, null).getCurrentStock()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> salesInvoiceService.completeInvoice(repB.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not enough stock");

        // the completed bill no longer counts as pending; the refused one still does
        assertThat(row(p, null).getReservedByDrafts()).isEqualByComparingTo("10");
        assertThat(row(p, null).getPendingBills()).isEqualTo(1);
    }

    @Test
    void aForgottenOldDraftDoesNotHoldStockForEver() {
        Product p = productWithStock("10");
        draft(p, 6, BusinessTime.today().minusDays(30));
        draft(p, 3, BusinessTime.today().minusDays(2));

        assertThat(row(p, null).getReservedByDrafts()).isEqualByComparingTo("3");
        assertThat(row(p, null).getPendingBills()).isEqualTo(1);
    }

    @Test
    void aProductNobodyHasOrderedShowsNothingReserved() {
        Product p = productWithStock("10");
        assertThat(row(p, null).getReservedByDrafts()).isEqualByComparingTo("0");
        assertThat(row(p, null).getPendingBills()).isZero();
    }
}
