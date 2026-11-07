package lk.aak.agency.service;

import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The goods check: every line of a purchase invoice is ticked after the goods were counted, a different quantity received
 * makes the line follow what arrived, and Complete is refused until every line is ticked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoodsReceivedCheckTest {

    @Autowired private PurchaseInvoiceService service;
    @Autowired private PurchaseInvoiceFileService fileService;
    @Autowired private PurchaseInvoiceRepository invoices;
    @Autowired private ProductRepository products;
    @Autowired private InventoryService inventory;
    @Autowired private MockMvc mockMvc;
    @Autowired private SystemUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Product product(String tag) {
        Product p = new Product();
        p.setCblProductCode("GR" + UUID.randomUUID().toString().substring(0, 8));
        p.setProductName("Goods check " + tag);
        p.setUnit("PCS");
        return products.save(p);
    }

    private PurchaseInvoiceItem line(Product p, String quantity, String unitPrice) {
        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setProduct(p);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    /** a draft with the original invoice file attached (completing requires one) */
    private PurchaseInvoice draft(PurchaseInvoiceItem... items) {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setDocumentNumber("GR-" + UUID.randomUUID().toString().substring(0, 8));
        invoice.setInvoiceDate(LocalDate.of(2026, 11, 7));
        PurchaseInvoice saved = service.saveInvoiceWithItems(invoice, List.of(items));
        service.attachInvoiceFile(saved.getId(), fileService.storeFile(new MockMultipartFile("file", "i.pdf", "application/pdf", "%PDF-1.4 test".getBytes())));
        return saved;
    }

    private List<PurchaseInvoiceItem> items(PurchaseInvoice invoice) {
        return service.getItemsByInvoiceId(invoice.getId());
    }

    private BigDecimal stockOf(Product p) {
        return inventory.getStockByProduct().getOrDefault(p.getId(), BigDecimal.ZERO);
    }

    @Test
    void completeIsRefusedUntilEveryLineIsTicked() {
        PurchaseInvoice invoice = draft(line(product("A"), "100", "10.00"), line(product("B"), "50", "4.00"));

        assertThatThrownBy(() -> service.completeInvoice(invoice.getId(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 of 2 line(s) are not ticked yet");

        var first = items(invoice).get(0);
        service.receiveItem(invoice.getId(), first.getId(), first.getQuantity(), "office1");
        assertThatThrownBy(() -> service.completeInvoice(invoice.getId(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 of 2 line(s) are not ticked yet");

        var second = items(invoice).get(1);
        service.receiveItem(invoice.getId(), second.getId(), second.getQuantity(), "office1");
        service.completeInvoice(invoice.getId(), true);
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(items(invoice).get(0).getReceivedBy()).isEqualTo("office1");
        assertThat(items(invoice).get(0).getReceivedAt()).isNotNull();
    }

    @Test
    void aShortDeliveryMakesTheLineFollowWhatArrived_andCanBeCorrectedBack() {
        Product p = product("S");
        PurchaseInvoice invoice = draft(line(p, "100", "10.00"));
        var item = items(invoice).get(0);

        service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("80"), "office1");

        var after = items(invoice).get(0);
        assertThat(after.getQuantity()).isEqualByComparingTo("80");
        assertThat(after.getAmount()).isEqualByComparingTo("800.00");
        assertThat(after.getExpectedQuantity()).isEqualByComparingTo("100");   // what the invoice said
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getSubtotal()).isEqualByComparingTo("800.00");
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getTotalAmount()).isEqualByComparingTo("800.00");

        // counted again, all 100 were there after all
        service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("100"), "office1");
        assertThat(items(invoice).get(0).getAmount()).isEqualByComparingTo("1000.00");

        // and a short delivery goes into stock as received, not as invoiced
        service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("80"), "office1");
        service.completeInvoice(invoice.getId(), true);
        assertThat(stockOf(p)).isEqualByComparingTo("80");
    }

    @Test
    void aLineWithCblsExactPrintedAmountKeepsItsExactnessWhenTheQuantityDiffers() {
        Product p = product("X");
        PurchaseInvoiceItem exact = line(p, "2880", "41.99");
        exact.setInvoicedAmount(new BigDecimal("120916.80"));     // CBL's printed amount: 240 DZ x 503.82
        PurchaseInvoice invoice = draft(exact);
        var item = items(invoice).get(0);
        assertThat(item.getAmount()).isEqualByComparingTo("120916.80");

        service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("2000"), "office1");
        assertThat(items(invoice).get(0).getAmount()).isEqualByComparingTo("83970.00");      // 41.985 x 2000

        service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("2880"), "office1");
        assertThat(items(invoice).get(0).getAmount()).as("back to the printed amount, to the cent").isEqualByComparingTo("120916.80");
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getSubtotal()).isEqualByComparingTo("120916.80");
    }

    @Test
    void takingTheTickOffMeansTheLineMustBeCountedAgain() {
        PurchaseInvoice invoice = draft(line(product("U"), "10", "5.00"));
        var item = items(invoice).get(0);
        service.receiveItem(invoice.getId(), item.getId(), item.getQuantity(), "office1");

        service.unreceiveItem(invoice.getId(), item.getId());

        assertThat(items(invoice).get(0).isReceivedConfirmed()).isFalse();
        assertThatThrownBy(() -> service.completeInvoice(invoice.getId(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not ticked yet");
    }

    @Test
    void anInvalidQuantityAndACompletedInvoiceAreRefused() {
        PurchaseInvoice invoice = draft(line(product("V"), "10", "5.00"));
        var item = items(invoice).get(0);

        assertThatThrownBy(() -> service.receiveItem(invoice.getId(), item.getId(), BigDecimal.ZERO, "office1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("remove the line with Edit lines");
        assertThatThrownBy(() -> service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("-3"), "office1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.receiveItem(invoice.getId(), 99999999L, new BigDecimal("1"), "office1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not on this purchase invoice");

        service.receiveItem(invoice.getId(), item.getId(), item.getQuantity(), "office1");
        service.completeInvoice(invoice.getId(), true);
        invoices.flush();
        assertThatThrownBy(() -> service.receiveItem(invoice.getId(), item.getId(), new BigDecimal("5"), "office1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completed");
    }

    @Test
    void theAddressWorksForOwnerAndOfficeButNotForReps() throws Exception {
        PurchaseInvoice invoice = draft(line(product("W"), "100", "10.00"));
        var item = items(invoice).get(0);
        String url = "/api/v1/purchase-invoices/" + invoice.getId() + "/items/" + item.getId() + "/receive";

        mockMvc.perform(post(url).header("Authorization", "Bearer " + token("SALES_REP")).contentType("application/json").content("{\"quantity\":80}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(url).header("Authorization", "Bearer " + token("OFFICE")).contentType("application/json").content("{\"quantity\":80}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].receivedConfirmed").value(true))
                .andExpect(jsonPath("$.items[0].quantity").value(80.0))
                .andExpect(jsonPath("$.items[0].expectedQuantity").value(100.0));
        mockMvc.perform(delete(url).header("Authorization", "Bearer " + token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].receivedConfirmed").value(false));
    }

    private String token(String role) {
        SystemUser user = users.save(new SystemUser("gr_" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode("password1"), "Test User", role, true));
        return jwtService.generateAccessToken(user.getUsername(), user.getRole());
    }
}
