package lk.aak.agency.credit;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lk.aak.agency.intake.CblInvoiceIntakeService;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.JwtService;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.service.PurchaseInvoiceService;
import lk.aak.agency.service.SupplierPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Supplier credit notes: read from the e-mailed PDF (one note per page, laid out like the real "4GB discount" notes), kept as
 * drafts, and only an approved note reduces what is owed to the supplier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SupplierCreditNoteTest {

    @Autowired private CreditNoteParser parser;
    @Autowired private CblInvoiceIntakeService intake;
    @Autowired private SupplierCreditNoteService service;
    @Autowired private SupplierCreditNoteRepository repository;
    @Autowired private SupplierPaymentService supplierPayments;
    @Autowired private PurchaseInvoiceService purchaseService;
    @Autowired private PurchaseInvoiceFileService fileService;
    @Autowired private ProductRepository products;
    @Autowired private MockMvc mockMvc;
    @Autowired private SystemUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    // ------------------------------------------------------------ made-up credit notes laid out like the real PDF

    private static String page(String number, String date, String remarks, String amount) {
        return "Plenty Foods (Pvt) Limited.\n"
                + "Sir John Kothalawala Mawatha,Kandawala,\n"
                + "Ratmalana,Sri Lanka. VAT Reg.No.114170879-7000\n"
                + "Te1:94-115004000, Doc.No.:" + number + "\n"
                + "Fax:94-114444669 Date   :" + date + "\n"
                + "Ref.Inv.No:\n"
                + "Ref.Inv.Date:19.05.2026\n"
                + "Credit Note\n"
                + "M/s: 10282\n"
                + "     A.A.K.AGENCIES - MAKOLA\n"
                + "Remarks     Amount Rs.\n"
                + remarks + "        " + amount + " \n"
                + "This is a system generated document & no signature is required\n";
    }

    private static byte[] pdf(String... pages) throws Exception {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 9);
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) doc.newPage();
            for (String line : pages[i].split("\n")) doc.add(new Paragraph(line, f));
        }
        doc.close();
        return out.toByteArray();
    }

    private static String num(String tag) {
        return "T" + UUID.randomUUID().toString().substring(0, 6) + tag;
    }

    // ------------------------------------------------------------ reading

    @Test
    void theReaderFindsEveryNoteWithItsNumberDateRemarksAndAmount() {
        var parsed = parser.parsePages(List.of(
                page("26SEP_X_1", "07.09.2026", "4GB DISCOUNT JULY 2026", "4,432.32"),
                page("26SEP_X_2", "30.09.2026", "4GB DISCOUNT AUG 2026", "3,099.06")));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.notes()).hasSize(2);
        var first = parsed.notes().get(0);
        assertThat(first.documentNumber()).isEqualTo("26SEP_X_1");
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(first.amount()).isEqualByComparingTo("4432.32");
        assertThat(first.remarks()).isEqualTo("4GB DISCOUNT JULY 2026");
        assertThat(first.issuer()).isEqualTo("Plenty Foods (Pvt) Limited");
        assertThat(first.refInvoiceNumber()).isNull();
        assertThat(first.refInvoiceDate()).isEqualTo(LocalDate.of(2026, 5, 19));
        assertThat(parsed.notes().get(1).amount()).isEqualByComparingTo("3099.06");
    }

    @Test
    void severalCreditNotesStackedOnOnePageAreAllRead() {
        // CBL's file has two credit notes to a page: reading one note per page silently dropped the second of each
        var parsed = parser.parsePages(List.of(
                page("26OCT_X_1", "19.10.2026", "Rebates", "2,720.00") + page("26OCT_X_2", "19.10.2026", "Rebates", "5,240.00"),
                page("26OCT_X_3", "19.10.2026", "Cho Dis", "459,712.00") + page("26OCT_X_4", "19.10.2026", "Cho Dis", "387,199.00")));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.notes()).extracting(CreditNoteParser.Note::documentNumber)
                .containsExactly("26OCT_X_1", "26OCT_X_2", "26OCT_X_3", "26OCT_X_4");
        assertThat(parsed.notes()).extracting(n -> n.amount().toPlainString())
                .containsExactly("2720.00", "5240.00", "459712.00", "387199.00");
        // every note keeps its own heading, not the previous note's
        assertThat(parsed.notes()).allSatisfy(n -> assertThat(n.issuer()).isEqualTo("Plenty Foods (Pvt) Limited"));
    }

    @Test
    void aSecondNoteOnAPageThatCannotBeReadRefusesTheFileInsteadOfBeingDropped() {
        var parsed = parser.parsePages(List.of(
                page("26OCT_X_1", "19.10.2026", "Rebates", "2,720.00") + page("26OCT_X_2", "19.10.2026", "Rebates", "oops")));

        assertThat(parsed.problems()).as("a file with one unreadable note is refused as a whole, not imported without it").isNotEmpty();
        assertThat(String.join(" ", parsed.problems())).contains("Page 1, credit note 2");
    }

    @Test
    void aPageItCannotReadExactlyMakesTheWholeFileARefusal() {
        var parsed = parser.parsePages(List.of(
                page("26SEP_X_1", "07.09.2026", "4GB DISCOUNT JULY 2026", "4,432.32"),
                page("26SEP_X_2", "30.09.2026", "4GB DISCOUNT AUG 2026", "oops")));

        assertThat(parsed.problems()).isNotEmpty();
        assertThat(String.join(" ", parsed.problems())).contains("Page 2");
    }

    // ------------------------------------------------------------ the e-mail intake

    @Test
    void anEmailedCreditNotePdfBecomesDraftsAndRepeatsAreRecognised() throws Exception {
        String a = num("A"), b = num("B"), c = num("C");
        byte[] file = pdf(page(a, "07.09.2026", "4GB DISCOUNT JULY 2026", "4,432.32"),
                page(b, "30.09.2026", "4GB DISCOUNT AUG 2026", "3,099.06"),
                page(c, "29.10.2026", "4GB DISCOUNT SEP 2026", "3,497.40"));

        var result = intake.importInvoice(file, "credit-notes.pdf");

        assertThat(result.problems()).isEmpty();
        assertThat(result.kind()).isEqualTo("CREDIT_NOTES");
        assertThat(result.status()).isEqualTo(CblInvoiceIntakeService.Status.CREATED);
        assertThat(result.lines()).isEqualTo(3);
        assertThat(result.total()).isEqualByComparingTo("11028.78");
        var note = repository.findByDocumentNumber(a).orElseThrow();
        assertThat(note.getStatus()).isEqualTo("DRAFT");
        assertThat(note.getSource()).isEqualTo("EMAIL");
        assertThat(note.hasFile()).isTrue();

        // the same file again: nothing new
        var again = intake.importInvoice(file, "credit-notes (1).pdf");
        assertThat(again.status()).isEqualTo(CblInvoiceIntakeService.Status.DUPLICATE);
        assertThat(again.kind()).isEqualTo("CREDIT_NOTES");

        // a file with one new note and two known ones adds only the new one
        String d = num("D");
        var more = intake.importInvoice(pdf(page(a, "07.09.2026", "4GB DISCOUNT JULY 2026", "4,432.32"),
                page(d, "30.11.2026", "4GB DISCOUNT OCT 2026", "2,000.00")), "more.pdf");
        assertThat(more.status()).isEqualTo(CblInvoiceIntakeService.Status.CREATED);
        assertThat(more.lines()).isEqualTo(1);
        assertThat(more.notes()).anyMatch(n -> n.contains("already recorded"));
    }

    @Test
    void aFileWithTwoNotesToAPageImportsEveryNoteAndSkipsTheOnesAlreadyKnown() throws Exception {
        String a = num("A"), b = num("B"), c = num("C"), d = num("D");
        // the first time only two of them were taken (the old one-note-per-page reading); now the whole file is read
        intake.importInvoice(pdf(page(a, "19.10.2026", "Rebates", "2,720.00"), page(c, "19.10.2026", "Cho Dis", "459,712.00")), "first.pdf");

        var result = intake.importInvoice(pdf(
                page(a, "19.10.2026", "Rebates", "2,720.00") + page(b, "19.10.2026", "Rebates", "5,240.00"),
                page(c, "19.10.2026", "Cho Dis", "459,712.00") + page(d, "19.10.2026", "Cho Dis", "387,199.00")), "both.pdf");

        assertThat(result.problems()).isEmpty();
        assertThat(result.status()).isEqualTo(CblInvoiceIntakeService.Status.CREATED);
        assertThat(result.lines()).isEqualTo(2);
        assertThat(result.total()).isEqualByComparingTo("392439.00");   // only the two new notes: 5,240 + 387,199 (but never the two already known)
        assertThat(result.notes()).filteredOn(n -> n.contains("already recorded")).hasSize(2);
        assertThat(repository.findByDocumentNumber(b).orElseThrow().getAmount()).isEqualByComparingTo("5240.00");
        assertThat(repository.findByDocumentNumber(d).orElseThrow().getAmount()).isEqualByComparingTo("387199.00");
        assertThat(repository.findByDocumentNumber(a).orElseThrow().getAmount()).isEqualByComparingTo("2720.00");
    }

    // ------------------------------------------------------------ approving changes what is owed

    private BigDecimal completedPurchase(String amount) {
        Product p = new Product();
        p.setCblProductCode("CN" + UUID.randomUUID().toString().substring(0, 8));
        p.setProductName("Credit note test product");
        p.setUnit("PCS");
        p = products.save(p);
        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setProduct(p);
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal(amount));
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setDocumentNumber("CN-" + UUID.randomUUID().toString().substring(0, 8));
        invoice.setInvoiceDate(LocalDate.of(2026, 11, 7));
        PurchaseInvoice saved = purchaseService.saveInvoiceWithItems(invoice, List.of(item));
        purchaseService.attachInvoiceFile(saved.getId(), fileService.storeFile(new MockMultipartFile("file", "i.pdf", "application/pdf", "%PDF-1.4 test".getBytes())));
        purchaseService.getItemsByInvoiceId(saved.getId()).forEach(i -> purchaseService.receiveItem(saved.getId(), i.getId(), i.getQuantity(), "tester"));
        purchaseService.completeInvoice(saved.getId(), true);
        return new BigDecimal(amount);
    }

    @Test
    void onlyAnApprovedCreditNoteReducesWhatIsOwed_andNeverBelowZero() {
        BigDecimal before = supplierPayments.getSupplierBalanceSummary().netOwed();
        completedPurchase("1000.00");
        BigDecimal owed = supplierPayments.getSupplierBalanceSummary().totalOwed();
        assertThat(supplierPayments.getSupplierBalanceSummary().netOwed()).isEqualByComparingTo(before.add(new BigDecimal("1000.00")));

        var note = service.create(num("M"), "Plenty Foods (Pvt) Limited", LocalDate.of(2026, 10, 1), new BigDecimal("300.00"), "4GB DISCOUNT", "office1");
        // a draft changes nothing
        assertThat(supplierPayments.getSupplierBalanceSummary().creditNotesTotal()).isEqualByComparingTo("0");
        assertThat(supplierPayments.getSupplierBalanceSummary().netOwed()).isEqualByComparingTo(owed);

        service.approve(note.getId(), "owner1");
        var summary = supplierPayments.getSupplierBalanceSummary();
        assertThat(summary.creditNotesTotal()).isEqualByComparingTo("300.00");
        assertThat(summary.totalOwed()).as("the invoices still owe the same").isEqualByComparingTo(owed);
        assertThat(summary.netOwed()).isEqualByComparingTo(owed.subtract(new BigDecimal("300.00")));

        // a credit bigger than everything owed shows as a credit in our favour, not as a hidden zero
        var big = service.create(num("B"), null, LocalDate.of(2026, 10, 2), owed.add(new BigDecimal("500.00")), "big", "office1");
        service.approve(big.getId(), "owner1");
        var after = supplierPayments.getSupplierBalanceSummary();
        assertThat(after.netOwed()).isEqualByComparingTo("0");
        assertThat(after.creditInFavour()).isEqualByComparingTo(new BigDecimal("300.00").add(owed.add(new BigDecimal("500.00"))).subtract(owed));
    }

    @Test
    void theRulesOfDraftAndApprovedNotes() {
        var note = service.create(num("R"), null, LocalDate.of(2026, 10, 1), new BigDecimal("100.00"), null, "office1");

        assertThatThrownBy(() -> service.create(note.getDocumentNumber(), null, LocalDate.of(2026, 10, 1), new BigDecimal("5"), null, "office1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already recorded");
        assertThatThrownBy(() -> service.create(num("Z"), null, LocalDate.of(2026, 10, 1), BigDecimal.ZERO, null, "office1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than 0");
        assertThatThrownBy(() -> service.create(" ", null, LocalDate.of(2026, 10, 1), BigDecimal.TEN, null, "office1"))
                .isInstanceOf(IllegalArgumentException.class);

        service.approve(note.getId(), "owner1");
        assertThatThrownBy(() -> service.approve(note.getId(), "owner1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already approved");
        assertThatThrownBy(() -> service.deleteDraft(note.getId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be deleted");

        var draft = service.create(num("D"), null, LocalDate.of(2026, 10, 1), new BigDecimal("50.00"), null, "office1");
        service.deleteDraft(draft.getId());
        assertThat(repository.findById(draft.getId())).isEmpty();
    }

    // ------------------------------------------------------------ the API and who may use it

    @Test
    void ownerAndOfficeCanUseTheCreditNoteScreensButRepsAndDriversCannot_andTheDashboardShowsTheNetFigure() throws Exception {
        var note = service.create(num("API"), null, LocalDate.of(2026, 10, 1), new BigDecimal("250.00"), "test", "office1");

        mockMvc.perform(get("/api/v1/supplier-credit-notes").header("Authorization", "Bearer " + token("SALES_REP"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/supplier-credit-notes").header("Authorization", "Bearer " + token("DRIVER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/supplier-credit-notes")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/supplier-credit-notes").header("Authorization", "Bearer " + token("OFFICE"))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/supplier-credit-notes/" + note.getId() + "/approve").header("Authorization", "Bearer " + token("SALES_REP")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/supplier-credit-notes/" + note.getId() + "/approve").header("Authorization", "Bearer " + token("OFFICE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        // the balance screen and the dashboard use the same net figure
        BigDecimal net = supplierPayments.getSupplierBalanceSummary().netOwed();
        mockMvc.perform(get("/api/v1/purchase-invoices/supplier-balance").header("Authorization", "Bearer " + token("OFFICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditNotesTotal").value(250.0))
                .andExpect(jsonPath("$.netOwed").value(net.doubleValue()));
        mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOwedToSupplier").value(net.doubleValue()));
    }

    private String token(String role) {
        SystemUser user = users.save(new SystemUser("cn_" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode("password1"), "Test User", role, true));
        return jwtService.generateAccessToken(user.getUsername(), user.getRole());
    }
}
