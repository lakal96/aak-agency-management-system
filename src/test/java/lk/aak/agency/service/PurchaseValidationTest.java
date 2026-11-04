package lk.aak.agency.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lk.aak.agency.dto.api.PurchaseInvoiceCreateRequest;
import lk.aak.agency.dto.api.PurchaseInvoiceItemRequest;
import lk.aak.agency.dto.api.StockAdjustmentRequest;
import lk.aak.agency.dto.api.SupplierPaymentRequest;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Request validation for purchase invoices, supplier payments and stock adjustments. */
@SpringBootTest
@Transactional   // roll back, so the shared in-memory database stays clean for other tests
class PurchaseValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Autowired
    private PurchaseInvoiceService purchaseInvoiceService;
    @Autowired
    private ProductRepository productRepository;

    private PurchaseInvoiceItemRequest item(String quantity, String unitPrice) {
        PurchaseInvoiceItemRequest item = new PurchaseInvoiceItemRequest();
        item.setProductId(1L);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    private PurchaseInvoiceCreateRequest invoice(String discount, String vat, PurchaseInvoiceItemRequest... items) {
        PurchaseInvoiceCreateRequest request = new PurchaseInvoiceCreateRequest();
        request.setDocumentNumber("PV-1");
        request.setInvoiceDate(BusinessTime.today());
        request.setSupplierName("CBL");
        request.setPlaceOfSupply("Kandy");
        request.setDiscountAmount(new BigDecimal(discount));
        request.setVatAmount(new BigDecimal(vat));
        request.setItems(List.of(items));
        return request;
    }

    private Set<String> messages(Object request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void validPurchaseRequestHasNoViolations() {
        assertThat(messages(invoice("0", "10.00", item("2", "5.50")))).isEmpty();
    }

    @Test
    void negativeDiscountAndVatAreRefused() {
        assertThat(messages(invoice("-1", "0", item("1", "1"))))
                .containsExactly("Discount cannot be negative.");
        assertThat(messages(invoice("0", "-0.01", item("1", "1"))))
                .containsExactly("VAT cannot be negative.");
    }

    @Test
    void absurdPurchaseValuesAreRefusedWithAClearMessage() {
        assertThat(messages(invoice("1000000000", "0", item("1", "1")))).anyMatch(m -> m.contains("Discount is too large"));
        assertThat(messages(invoice("0", "1000000000", item("1", "1")))).anyMatch(m -> m.contains("VAT is too large"));
        assertThat(messages(invoice("0", "0", item("10000001", "1")))).anyMatch(m -> m.contains("Quantity is too large"));
        assertThat(messages(invoice("0", "0", item("1", "1000000000")))).anyMatch(m -> m.contains("Unit price is too large"));
        assertThat(messages(invoice("0", "0", item("0", "1")))).contains("Quantity must be greater than zero.");
        assertThat(messages(invoice("0", "0", item("1", "-1")))).contains("Unit price cannot be negative.");
    }

    @Test
    void amountsAreRoundedToTwoDecimalsHalfUp() {
        PurchaseInvoiceItemRequest item = item("3", "10.005");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("10.01");
        assertThat(item.getUnitPrice().scale()).isEqualTo(2);

        PurchaseInvoiceCreateRequest request = invoice("1.005", "2.004", item);
        assertThat(request.getDiscountAmount()).isEqualByComparingTo("1.01");
        assertThat(request.getVatAmount()).isEqualByComparingTo("2.00");
    }

    @Test
    void lineAmountIsRoundedSoDraftAndCompletedTotalsAgree() {
        Product product = new Product();
        product.setCblProductCode("PV-TOTAL-1");
        product.setProductName("Rounding product");
        product.setUnit("PCS");
        product = productRepository.save(product);

        PurchaseInvoiceItem line = new PurchaseInvoiceItem();
        line.setProduct(product);
        line.setQuantity(new BigDecimal("3"));
        line.setUnitPrice(new BigDecimal("10.005"));   // reached the service with 3 decimals (old form path)

        PurchaseInvoice draft = new PurchaseInvoice();
        draft.setDocumentNumber("PV-ROUND-1");
        draft.setInvoiceDate(BusinessTime.today());
        draft.setPlaceOfSupply("Kandy");
        draft.setDiscountAmount(BigDecimal.ZERO);
        draft.setVatAmount(BigDecimal.ZERO);

        PurchaseInvoice saved = purchaseInvoiceService.saveInvoiceWithItems(draft, List.of(line));

        PurchaseInvoiceItem stored = purchaseInvoiceService.getItemsByInvoiceId(saved.getId()).get(0);
        assertThat(stored.getAmount()).isEqualByComparingTo("30.02");          // 30.015 half up
        assertThat(saved.getSubtotal()).isEqualByComparingTo(stored.getAmount());
        assertThat(saved.getTotalAmount()).isEqualByComparingTo(stored.getAmount());
    }

    @Test
    void discountLargerThanTheInvoiceValueIsRefused() {
        Product product = new Product();
        product.setCblProductCode("PV-DISC-1");
        product.setProductName("Discount product");
        product.setUnit("PCS");
        product = productRepository.save(product);

        PurchaseInvoiceItem line = new PurchaseInvoiceItem();
        line.setProduct(product);
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("10.00"));

        PurchaseInvoice draft = new PurchaseInvoice();
        draft.setDocumentNumber("PV-DISC-DOC");
        draft.setInvoiceDate(BusinessTime.today());
        draft.setPlaceOfSupply("Kandy");
        draft.setDiscountAmount(new BigDecimal("10.01"));

        assertThatThrownBy(() -> purchaseInvoiceService.saveInvoiceWithItems(draft, List.of(line)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discount amount cannot be greater");
    }

    private SupplierPaymentRequest payment(String method, String amount, java.time.LocalDate date) {
        SupplierPaymentRequest request = new SupplierPaymentRequest();
        request.setPurchaseInvoiceId(1L);
        request.setAmount(new BigDecimal(amount));
        request.setPaymentDate(date);
        request.setPaymentMethod(method);
        return request;
    }

    @Test
    void supplierPaymentMethodMustBeARealOne_caseInsensitive() {
        assertThat(messages(payment("cash", "10", BusinessTime.today()))).isEmpty();
        assertThat(payment(" bank_transfer ", "10", BusinessTime.today()).getPaymentMethod()).isEqualTo("BANK_TRANSFER");
        assertThat(messages(payment("BANANA", "10", BusinessTime.today())))
                .containsExactly("The payment method must be Cash, Cheque or Bank transfer.");
        assertThat(messages(payment("", "10", BusinessTime.today()))).contains("Select a payment method.");
    }

    @Test
    void supplierPaymentDateCannotBeInTheFuture_andAmountIsRoundedAndCapped() {
        assertThat(messages(payment("CASH", "10", BusinessTime.today().plusDays(1))))
                .containsExactly("The payment date cannot be in the future.");
        assertThat(messages(payment("CASH", "10", BusinessTime.today().minusDays(30)))).isEmpty();

        assertThat(payment("CASH", "10.005", BusinessTime.today()).getAmount()).isEqualByComparingTo("10.01");
        assertThat(messages(payment("CASH", "1000000000", BusinessTime.today())))
                .anyMatch(m -> m.contains("too large"));
    }

    @Test
    void stockAdjustmentRequestNeedsANoteAndASaneQuantity() {
        StockAdjustmentRequest request = new StockAdjustmentRequest();
        request.setProductId(1L);
        request.setAdjustmentType("DAMAGED");
        request.setQuantity(new BigDecimal("2"));
        assertThat(messages(request)).containsExactly("Please write a short note saying why the stock is changing.");

        request.setNotes("2 packs crushed");
        assertThat(messages(request)).isEmpty();

        request.setQuantity(new BigDecimal("1000000"));
        assertThat(messages(request)).anyMatch(m -> m.contains("too large"));
    }
}
