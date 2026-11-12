package lk.aak.agency.intake;

import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.service.AuditLogService;
import lk.aak.agency.service.ProductService;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.service.PurchaseInvoiceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a CBL invoice PDF that arrived by e-mail into a DRAFT purchase invoice. Nothing reaches the stock or the
 * amount owed to CBL until a person compares the draft with the attached PDF and completes it, exactly as for an
 * invoice typed in by hand.
 */
@Service
public class CblInvoiceIntakeService {

    public static final String INTAKE_USER = "email-intake";
    private static final BigDecimal MAX_PDF_BYTES = BigDecimal.valueOf(8L * 1024 * 1024);

    public enum Status { CREATED, DUPLICATE, EMPTY, REJECTED }

    /** `kind` says what the file was: INVOICE (a product invoice) or CREDIT_NOTES (one or more supplier credit notes). */
    public record Result(Status status, Long invoiceId, String documentNumber, int lines, BigDecimal total,
                         List<String> notes, List<String> problems, String kind, List<String> layoutWarnings) {

        /** Without layout warnings (everything except an invoice whose usual headings are missing). */
        public Result(Status status, Long invoiceId, String documentNumber, int lines, BigDecimal total,
                      List<String> notes, List<String> problems, String kind) {
            this(status, invoiceId, documentNumber, lines, total, notes, problems, kind, List.of());
        }

        public Result withLayoutWarnings(List<String> warnings) {
            return new Result(status, invoiceId, documentNumber, lines, total, notes, problems, kind, warnings == null ? List.of() : List.copyOf(warnings));
        }

        public Result(Status status, Long invoiceId, String documentNumber, int lines, BigDecimal total,
                      List<String> notes, List<String> problems) {
            this(status, invoiceId, documentNumber, lines, total, notes, problems, "INVOICE");
        }
    }

    private final CblInvoiceParser parser;
    private final lk.aak.agency.credit.CreditNoteParser creditNoteParser;
    private final lk.aak.agency.credit.SupplierCreditNoteService creditNoteService;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final PurchaseInvoiceService purchaseInvoiceService;
    private final PurchaseInvoiceFileService fileService;
    private final AuditLogService auditLogService;

    private final Object lock = new Object();
    private final TransactionTemplate transaction;

    public CblInvoiceIntakeService(CblInvoiceParser parser, lk.aak.agency.credit.CreditNoteParser creditNoteParser,
                                   lk.aak.agency.credit.SupplierCreditNoteService creditNoteService, ProductRepository productRepository, ProductService productService,
                                   PurchaseInvoiceRepository purchaseInvoiceRepository,
                                   PurchaseInvoiceService purchaseInvoiceService, PurchaseInvoiceFileService fileService,
                                   AuditLogService auditLogService, PlatformTransactionManager transactionManager) {
        this.parser = parser;
        this.creditNoteParser = creditNoteParser;
        this.creditNoteService = creditNoteService;
        this.productRepository = productRepository;
        this.productService = productService;
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.purchaseInvoiceService = purchaseInvoiceService;
        this.fileService = fileService;
        this.auditLogService = auditLogService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result importInvoice(byte[] pdf, String fileName) {

        if (pdf == null || pdf.length < 100 || pdf.length > MAX_PDF_BYTES.longValue()
                || !new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF")) {
            return rejected(null, List.of("That is not a PDF file (or it is empty or too large)."));
        }

        // credit notes (the supplier's monthly discount notes, one per page) are a different document: draft credits
        var credit = creditNoteParser.parse(pdf);
        if (credit.isPresent()) {
            return importCreditNotes(credit.get(), pdf, fileName);
        }

        CblDocument document = parser.parse(pdf, code -> productRepository.findByCblProductCode(code).map(Product::getProductName).orElse(null));
        if (!document.readable()) {
            return rejected(document.invoice() == null ? null : document.invoice().taxInvoiceNumber(), document.problems());
        }

        CblDocument.Invoice parsed = document.invoice();
        if (parsed.lines().isEmpty()) {
            // a valid invoice with no products and all totals zero: nothing to import, and nothing to worry about
            return new Result(Status.EMPTY, null, parsed.taxInvoiceNumber(), 0, BigDecimal.ZERO,
                    List.of("This invoice has no products on it (all totals are 0.00), so there is nothing to import."), List.of());
        }
        // one at a time: two copies of the same e-mail must never make two drafts
        synchronized (lock) {
            Optional<PurchaseInvoice> existing = purchaseInvoiceRepository.findByDocumentNumber(parsed.taxInvoiceNumber());
            if (existing.isPresent()) {
                PurchaseInvoice old = existing.get();
                return new Result(Status.DUPLICATE, old.getId(), old.getDocumentNumber(), parsed.lines().size(),
                        old.getTotalAmount(), List.of("This invoice was already imported (status " + old.getStatus() + "). Nothing was changed."), List.of());
            }
            try {
                // all or nothing: the draft, its lines, any new products and the attached PDF
                return transaction.execute(status -> createDraft(parsed, pdf, fileName, document.layoutWarnings()));
            } catch (IllegalArgumentException | IllegalStateException refused) {
                return rejected(parsed.taxInvoiceNumber(), List.of(String.valueOf(refused.getMessage())));
            } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                return rejected(parsed.taxInvoiceNumber(), List.of("The system refused to store this invoice (it may have been imported at the same moment). Nothing was changed."));
            }
        }
    }

    private Result createDraft(CblDocument.Invoice parsed, byte[] pdf, String fileName, List<String> layoutWarnings) {

        List<String> notes = new ArrayList<>();
        // the usual headings are not all there: the draft says so at the top of its notes, where the person doing the goods check will see it
        for (String warning : layoutWarnings) notes.add("LAYOUT CHECK: " + warning + " Compare EVERY line and the total with the PDF before completing.");
        List<PurchaseInvoiceItem> items = new ArrayList<>();
        List<String> created = new ArrayList<>();
        List<String> adopted = new ArrayList<>();

        for (CblDocument.Line line : parsed.lines()) {
            Product product = resolveProduct(line, parsed.taxInvoiceNumber(), created, adopted);

            PurchaseInvoiceItem item = new PurchaseInvoiceItem();
            item.setProduct(product);
            item.setQuantity(line.pieces());
            item.setUnitPrice(line.amount().divide(line.pieces(), 2, RoundingMode.HALF_UP));
            item.setInvoicedQuantity(line.quantity());
            item.setInvoicedUnit(line.unit());
            item.setInvoicedUnitPrice(line.unitPrice());
            item.setInvoicedAmount(line.amount());
            items.add(item);
        }

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setDocumentNumber(parsed.taxInvoiceNumber());
        invoice.setTaxInvoiceNumber(parsed.taxInvoiceNumber());
        invoice.setPoNumber(parsed.poNumber());
        invoice.setInvoiceDate(parsed.invoiceDate());
        invoice.setDeliveryDate(parsed.supplyDate() != null ? parsed.supplyDate() : parsed.invoiceDate());
        invoice.setTerritory(parsed.territory());
        invoice.setDiscountAmount(parsed.discount());
        invoice.setVatAmount(parsed.totalIncludingVat().subtract(parsed.totalValueOfSupply()).max(BigDecimal.ZERO));
        invoice.setPaymentMethod(paymentMethodOf(parsed.paymentMode()));
        invoice.setSource("EMAIL");

        if (!created.isEmpty()) notes.add(created.size() + " new product(s) were created from this invoice (set to Inactive, price 0 - set the price and make them Active before billing): " + String.join("; ", created));
        if (!adopted.isEmpty()) notes.add(adopted.size() + " product(s) matched by name and given their real CBL code: " + String.join("; ", adopted));
        invoice.setNotes(summary(parsed, notes));

        PurchaseInvoice saved = purchaseInvoiceService.saveInvoiceWithItems(invoice, items);

        // the totals the system now holds must be CBL's totals to the cent, or nothing is kept
        if (saved.getSubtotal().compareTo(parsed.totalOrderValue()) != 0
                || saved.getTotalAmount().compareTo(parsed.totalValueOfSupply().add(saved.getVatAmount())) != 0) {
            throw new IllegalStateException("The draft total " + saved.getTotalAmount() + " does not equal the invoice total "
                    + parsed.totalValueOfSupply() + " - nothing was saved.");
        }

        var stored = fileService.storeFile(new ByteArrayMultipartFile(safeName(fileName, parsed.taxInvoiceNumber()), pdf));
        try {
            purchaseInvoiceService.attachInvoiceFile(saved.getId(), stored);
        } catch (RuntimeException e) {
            fileService.deleteFile(stored.storedFileName());
            throw e;
        }

        auditLogService.recordAs(INTAKE_USER, "PURCHASE_INVOICE_EMAILED", "PurchaseInvoice", saved.getId(),
                "Draft made from the e-mailed CBL invoice " + parsed.taxInvoiceNumber() + " (" + items.size() + " lines, total " + saved.getTotalAmount() + ")");

        return new Result(Status.CREATED, saved.getId(), saved.getDocumentNumber(), items.size(), saved.getTotalAmount(), notes, List.of()).withLayoutWarnings(layoutWarnings);
    }

    /** By CBL code; else a product still on a TEMP code with exactly this name (it gets the real code); else a new one. */
    private Product resolveProduct(CblDocument.Line line, String invoiceNumber, List<String> created, List<String> adopted) {

        Optional<Product> byCode = productRepository.findByCblProductCode(line.code());
        if (byCode.isPresent()) return byCode.get();

        String wanted = normalise(line.description());
        List<Product> sameName = productRepository.findByCblProductCodeStartingWith("TEMP-").stream()
                .filter(p -> normalise(p.getProductName()).equals(wanted))
                .toList();
        if (sameName.size() == 1) {
            Product product = sameName.get(0);
            adopted.add(product.getCblProductCode() + " -> " + line.code());
            product.setCblProductCode(line.code());
            return productService.saveProduct(product);
        }

        Product product = new Product();
        product.setCblProductCode(line.code());
        product.setProductName(line.description());
        product.setBrand("CBL");
        product.setNetWeight(line.weight().toLowerCase(Locale.ROOT));
        product.setUnit("PCS");
        product.setMrp(BigDecimal.ZERO);
        product.setStandardSellingPrice(BigDecimal.ZERO);
        product.setReorderLevel(BigDecimal.ZERO);
        product.setStatus("INACTIVE");
        product.setNotes("Created from CBL invoice " + invoiceNumber + ". Set the price and make the product Active.");
        created.add(line.code() + " " + line.description());
        return productService.saveProduct(product);
    }

    private static String summary(CblDocument.Invoice parsed, List<String> notes) {
        StringBuilder text = new StringBuilder();
        text.append("MADE AUTOMATICALLY from the e-mailed CBL invoice. Open the attached PDF, compare it with these lines, then tick the ")
                .append("verification box and press Complete - only then does the stock go up and the amount owed to CBL increase.\n");
        text.append(parsed.lines().size()).append(" product lines. Quantities are in PIECES (an invoiced dozen is 12 pieces); each line keeps CBL's own ")
                .append("quantity, unit and price, and the exact amount CBL printed.\n");
        text.append("CBL's printed totals: rows Rs ").append(parsed.totalOrderValue().toPlainString())
                .append(", discount Rs ").append(parsed.discount().toPlainString())
                .append(", TOTAL Rs ").append(parsed.totalValueOfSupply().toPlainString())
                .append(" - the total of this draft must still equal this unless you changed a line on purpose.\n");
        if (parsed.deliveryNotes() != null) text.append("CBL delivery notes: ").append(parsed.deliveryNotes()).append(".\n");
        for (String note : notes) text.append(note).append('\n');
        return text.toString().trim();
    }

    private static String paymentMethodOf(String mode) {
        if (mode == null) return null;
        String upper = mode.toUpperCase(Locale.ROOT);
        if (upper.contains("BANK")) return "BANK_TRANSFER";
        if (upper.contains("CHEQUE") || upper.contains("CHECK")) return "CHEQUE";
        if (upper.contains("CASH")) return "CASH";
        return null;
    }

    static String normalise(String name) {
        return name == null ? "" : name.toUpperCase(Locale.ROOT).replace("G.NET", "G").replaceAll("[^A-Z0-9]", "");
    }

    private static String safeName(String fileName, String invoiceNumber) {
        String base = fileName == null ? "" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.toLowerCase(Locale.ROOT).endsWith(".pdf") && base.length() > 4 ? base : "CBL-invoice-" + invoiceNumber + ".pdf";
    }

    private Result importCreditNotes(lk.aak.agency.credit.CreditNoteParser.Parsed parsed, byte[] pdf, String fileName) {

        if (!parsed.problems().isEmpty() || parsed.notes().isEmpty()) {
            return new Result(Status.REJECTED, null, null, 0, null, List.of(), parsed.problems(), "CREDIT_NOTES");
        }
        synchronized (lock) {
            try {
                var outcome = transaction.execute(status -> creditNoteService.importNotes(parsed.notes(),
                        new ByteArrayMultipartFile(safeName(fileName, parsed.notes().get(0).documentNumber()), pdf), INTAKE_USER));
                List<String> notes = new ArrayList<>();
                BigDecimal total = BigDecimal.ZERO;
                for (var note : outcome.created()) {
                    notes.add("Credit note " + note.getDocumentNumber() + " Rs " + note.getAmount().toPlainString()
                            + (note.getRemarks() == null || note.getRemarks().isBlank() ? "" : " (" + note.getRemarks() + ")")
                            + ": draft made. Check it and approve it under Supplier balance.");
                    total = total.add(note.getAmount());
                }
                for (String duplicate : outcome.duplicates()) {
                    notes.add("Credit note " + duplicate + " was already recorded. Nothing changed.");
                }
                boolean any = !outcome.created().isEmpty();
                String number = any ? outcome.created().get(0).getDocumentNumber() : outcome.duplicates().get(0);
                return new Result(any ? Status.CREATED : Status.DUPLICATE, null, number,
                        any ? outcome.created().size() : outcome.duplicates().size(), total, notes, List.of(), "CREDIT_NOTES");
            } catch (IllegalArgumentException | IllegalStateException refused) {
                return new Result(Status.REJECTED, null, null, 0, null, List.of(), List.of(String.valueOf(refused.getMessage())), "CREDIT_NOTES");
            } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                return new Result(Status.REJECTED, null, null, 0, null, List.of(),
                        List.of("The system refused to store these credit notes (they may have been imported at the same moment). Nothing was changed."), "CREDIT_NOTES");
            }
        }
    }

    private static Result rejected(String documentNumber, List<String> problems) {
        return new Result(Status.REJECTED, null, documentNumber, 0, null, List.of(), problems);
    }
}
