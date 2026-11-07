package lk.aak.agency.batch;

import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.JwtService;
import lk.aak.agency.service.PurchaseInvoiceService;
import lk.aak.agency.service.StockAdjustmentService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Completing a purchase records a batch with its cost and the +10% price; what is in stock is counted from the newest batch. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StockBatchTest {

    @Autowired private PurchaseInvoiceService purchaseService;
    @Autowired private StockBatchService batchService;
    @Autowired private StockBatchRepository batchRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StockAdjustmentService adjustmentService;
    @Autowired private MockMvc mockMvc;
    @Autowired private SystemUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private lk.aak.agency.service.PurchaseInvoiceFileService fileService;

    private Product product(String code) {
        Product p = new Product();
        p.setCblProductCode(code + UUID.randomUUID().toString().substring(0, 6));
        p.setProductName("Batch test " + code);
        p.setUnit("PCS");
        p.setMrp(new BigDecimal("100"));
        p.setStandardSellingPrice(new BigDecimal("90"));
        p.setReorderLevel(BigDecimal.ZERO);
        p.setStatus("ACTIVE");
        return productRepository.save(p);
    }

    private PurchaseInvoiceItem line(Product p, String quantity, String unitPrice) {
        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setProduct(p);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    /** a purchase invoice with one line, completed (a real file is attached because completing requires one) */
    private PurchaseInvoice complete(String number, LocalDate date, PurchaseInvoiceItem... items) {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setDocumentNumber(number + UUID.randomUUID().toString().substring(0, 6));
        invoice.setInvoiceDate(date);
        invoice.setDeliveryDate(date);
        PurchaseInvoice saved = purchaseService.saveInvoiceWithItems(invoice, List.of(items));
        var stored = fileService.storeFile(new MockMultipartFile("file", "i.pdf", "application/pdf", "%PDF-1.4 test".getBytes()));
        purchaseService.attachInvoiceFile(saved.getId(), stored);
        purchaseService.getItemsByInvoiceId(saved.getId()).forEach(i -> purchaseService.receiveItem(saved.getId(), i.getId(), i.getQuantity(), "tester"));
        purchaseService.completeInvoice(saved.getId(), true);
        return saved;
    }

    @Test
    void completingAPurchaseRecordsABatchWithItsCostAndThePricePlusTenPercent() {
        Product p = product("A");

        PurchaseInvoice invoice = complete("B1", LocalDate.of(2026, 11, 1), line(p, "600", "44.00"));

        List<StockBatch> batches = batchRepository.findByPurchaseInvoiceIdOrderByIdAsc(invoice.getId());
        assertThat(batches).hasSize(1);
        StockBatch b = batches.get(0);
        assertThat(b.getReceivedQuantity()).isEqualByComparingTo("600");
        assertThat(b.getUnitCost()).isEqualByComparingTo("44.0000");
        assertThat(b.getExactPrice()).isEqualByComparingTo("48.40");
        assertThat(b.getSuggestedPrice()).isEqualByComparingTo("48");   // nearest rupee
        // nothing about billing changed: the product keeps its own selling price
        assertThat(productRepository.findById(p.getId()).orElseThrow().getStandardSellingPrice()).isEqualByComparingTo("90");
    }

    @Test
    void aDraftRecordsNothingAndCompletingTwiceNeverDoublesABatch() {
        Product p = product("B");
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setDocumentNumber("DRAFT" + UUID.randomUUID().toString().substring(0, 6));
        invoice.setInvoiceDate(LocalDate.of(2026, 11, 2));
        PurchaseInvoice saved = purchaseService.saveInvoiceWithItems(invoice, List.of(line(p, "10", "5")));
        assertThat(batchRepository.findByPurchaseInvoiceIdOrderByIdAsc(saved.getId())).isEmpty();

        var items = purchaseService.getItemsByInvoiceId(saved.getId());
        batchService.recordBatches(saved, items);
        batchService.recordBatches(saved, items);
        assertThat(batchRepository.findByPurchaseInvoiceIdOrderByIdAsc(saved.getId())).hasSize(1);
    }

    @Test
    void whatIsInStockIsCountedBackFromTheNewestBatchAndOpeningStockIsListedWithoutACost() {
        Product p = product("C");
        complete("OLD", LocalDate.of(2026, 11, 1), line(p, "100", "40.00"));   // cost 40 -> price 44
        complete("NEW", LocalDate.of(2026, 11, 8), line(p, "500", "44.00"));   // cost 44 -> price 48

        // nothing sold yet: both batches are fully in stock
        var rows = batchService.view(true, "Batch test C").rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).remainingQuantity()).isEqualByComparingTo("100");
        assertThat(rows.get(0).suggestedPrice()).isEqualByComparingTo("44");
        assertThat(rows.get(1).remainingQuantity()).isEqualByComparingTo("500");
        assertThat(rows.get(1).suggestedPrice()).isEqualByComparingTo("48");

        // 150 pieces are damaged: oldest first, so the old batch is gone and the new one has 450 left
        var adjustment = new lk.aak.agency.model.StockAdjustment();
        adjustment.setAdjustmentType("DAMAGED");
        adjustment.setQuantity(new BigDecimal("150"));
        adjustment.setNotes("test");
        adjustmentService.saveAdjustment(adjustment, p.getId(), "tester");

        var after = batchService.view(true, "Batch test C");
        assertThat(after.rows()).hasSize(1);
        assertThat(after.rows().get(0).documentNumber()).startsWith("NEW");
        assertThat(after.rows().get(0).remainingQuantity()).isEqualByComparingTo("450");
        assertThat(batchService.view(false, "Batch test C").rows()).hasSize(2);
        assertThat(after.unbatched()).isEmpty();
    }

    @Test
    void onlyOwnerAndOfficeCanSeeBatchCosts() throws Exception {
        for (String role : List.of("ADMIN", "OFFICE")) {
            mockMvc.perform(get("/api/v1/stock-batches").header("Authorization", "Bearer " + token(role))).andExpect(status().isOk());
        }
        for (String role : List.of("SALES_REP", "DRIVER")) {
            mockMvc.perform(get("/api/v1/stock-batches").header("Authorization", "Bearer " + token(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/stock-batches")).andExpect(status().isUnauthorized());
    }

    private String token(String role) {
        SystemUser user = userRepository.save(new SystemUser("b_" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode("password1"), "Test User", role, true));
        return jwtService.generateAccessToken(user.getUsername(), user.getRole());
    }
}
