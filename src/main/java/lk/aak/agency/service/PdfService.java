package lk.aak.agency.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.FontSelector;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Generates printable PDF copies of sales/purchase invoices (OpenPDF, no external renderer needed).
 *
 * Latin letters, digits and punctuation use the built-in Helvetica; Sinhala letters (customer names,
 * addresses, areas) use the bundled Noto Sans Sinhala (SIL OFL, see resources/fonts/OFL.txt), embedded
 * in the PDF. Amounts print as "Rs. 1,234.56" and dates as dd/mm/yyyy.
 */
@Service
public class PdfService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Color BRAND_COLOR = new Color(0x21, 0x3F, 0x66);
    private static final Color TABLE_HEADER_BACKGROUND = new Color(0xE5, 0xED, 0xF8);

    private static final Style TITLE_FONT = new Style(20, true, BRAND_COLOR);
    private static final Style SUBTITLE_FONT = new Style(10, false, Color.DARK_GRAY);
    private static final Style SECTION_FONT = new Style(12, true, BRAND_COLOR);
    private static final Style LABEL_FONT = new Style(8, true, Color.GRAY);
    private static final Style VALUE_FONT = new Style(10, false, Color.BLACK);
    private static final Style TABLE_HEADER_FONT = new Style(9, true, BRAND_COLOR);
    private static final Style TABLE_CELL_FONT = new Style(9, false, Color.BLACK);
    private static final Style TOTAL_LABEL_FONT = new Style(10, false, Color.DARK_GRAY);
    private static final Style TOTAL_VALUE_FONT = new Style(12, true, BRAND_COLOR);

    private static final String SINHALA_REGULAR = "/fonts/NotoSansSinhala-Regular.ttf";
    private static final String SINHALA_BOLD = "/fonts/NotoSansSinhala-Bold.ttf";

    private static volatile byte[] sinhalaRegularBytes;
    private static volatile byte[] sinhalaBoldBytes;

    /** Size, weight and colour of a piece of text (the typeface is chosen per character). */
    private record Style(float size, boolean bold, Color color) {
    }

    /**
     * The fonts of ONE document. BaseFont objects keep per-document subset state, so each PDF gets its
     * own (the TTF bytes themselves are read once and cached).
     */
    private static final class Fonts {

        private final BaseFont latin;
        private final BaseFont latinBold;
        private final BaseFont sinhala;
        private final BaseFont sinhalaBold;

        Fonts() {
            try {
                latin = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
                latinBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
                sinhala = embedded("NotoSansSinhala-Regular.ttf", SINHALA_REGULAR, false);
                sinhalaBold = embedded("NotoSansSinhala-Bold.ttf", SINHALA_BOLD, true);
            } catch (DocumentException | IOException exception) {
                throw new IllegalStateException("Could not load the PDF fonts.", exception);
            }
        }

        private static BaseFont embedded(String name, String resource, boolean bold) throws DocumentException, IOException {
            byte[] bytes = bold ? sinhalaBoldBytes : sinhalaRegularBytes;
            if (bytes == null) {
                try (InputStream in = PdfService.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IOException("Font file missing from the application: " + resource);
                    }
                    bytes = StreamUtils.copyToByteArray(in);
                }
                if (bold) {
                    sinhalaBoldBytes = bytes;
                } else {
                    sinhalaRegularBytes = bytes;
                }
            }
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bytes, null);
        }

        /** Text in the given style: Latin from Helvetica, Sinhala (and anything else Helvetica lacks) from Noto. */
        Phrase phrase(String text, Style style) {
            FontSelector selector = new FontSelector();
            selector.addFont(new Font(style.bold() ? latinBold : latin, style.size(), Font.NORMAL, style.color()));
            selector.addFont(new Font(style.bold() ? sinhalaBold : sinhala, style.size(), Font.NORMAL, style.color()));
            return selector.process(SinhalaShaping.toVisualOrder(text == null ? "" : text));
        }

        Paragraph paragraph(String text, Style style) {
            Paragraph paragraph = new Paragraph(phrase(text, style));
            paragraph.setLeading(style.size() * 1.5f);
            return paragraph;
        }
    }

    public byte[] generateSalesInvoicePdf(
            SalesInvoice invoice,
            List<SalesInvoiceItem> items,
            BigDecimal paidAmount,
            BigDecimal balance) {

        Customer customer = invoice.getCustomer();

        Document document = newDocument();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Fonts fonts = new Fonts();

        try {
            PdfWriter.getInstance(document, outputStream);
            document.open();

            addLetterhead(document, fonts, "SALES INVOICE", invoice.getInvoiceNumber(), formatDate(invoice.getInvoiceDate()));

            addKeyValueGrid(document, fonts, new String[][]{
                    {"Customer Code", customer.getCustomerCode()},
                    {"Customer Name", customer.getCustomerName()},
                    {"Area", valueOrDash(customer.getArea())},
                    {"Phone", valueOrDash(customer.getPhone())},
                    {"Sale Type", invoice.getSaleType()},
                    {"Due Date", formatDate(invoice.getDueDate())},
                    {"Route Code", valueOrDash(invoice.getRouteCode())},
                    {"Payment Status", invoice.getPaymentStatus()}
            });

            addSectionTitle(document, fonts, "Sold Products");
            addItemsTable(
                    document,
                    fonts,
                    new String[]{"CBL Code", "Product", "Quantity", "Unit", "Unit Price", "Amount"},
                    new int[]{15, 35, 12, 10, 13, 15},
                    items.stream().map(item -> new String[]{
                            item.getProduct().getCblProductCode(),
                            item.getProduct().getProductName(),
                            formatQuantity(item.getQuantity()),
                            item.getUnit(),
                            formatMoney(item.getUnitPrice()),
                            formatMoney(item.getAmount())
                    }).toList()
            );

            addTotalsTable(document, fonts, new String[][]{
                    {"Gross Amount", formatMoney(invoice.getGrossAmount())},
                    {"Discount", formatMoney(invoice.getDiscountAmount())},
                    {"Returns", formatMoney(invoice.getReturnAmount())},
                    {"Net Amount", formatMoney(invoice.getNetAmount())},
                    {"Paid", formatMoney(paidAmount)},
                    {"Balance", formatMoney(balance)}
            });

            addSignatureFooter(document, fonts);

            document.close();
            return outputStream.toByteArray();

        } catch (DocumentException exception) {
            throw new IllegalStateException("Could not generate the sales invoice PDF.", exception);
        }
    }

    public byte[] generatePurchaseInvoicePdf(PurchaseInvoice invoice, List<PurchaseInvoiceItem> items) {

        Document document = newDocument();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Fonts fonts = new Fonts();

        try {
            PdfWriter.getInstance(document, outputStream);
            document.open();

            addLetterhead(document, fonts, "PURCHASE INVOICE", invoice.getDocumentNumber(), formatDate(invoice.getInvoiceDate()));

            addKeyValueGrid(document, fonts, new String[][]{
                    {"Supplier", invoice.getSupplierName()},
                    {"Tax Invoice No.", valueOrDash(invoice.getTaxInvoiceNumber())},
                    {"PO Number", valueOrDash(invoice.getPoNumber())},
                    {"Delivery Date", formatDate(invoice.getDeliveryDate())},
                    {"Territory", valueOrDash(invoice.getTerritory())},
                    {"Place of Supply", invoice.getPlaceOfSupply()},
                    {"Payment Method", valueOrDash(invoice.getPaymentMethod())},
                    {"Status", invoice.getStatus()}
            });

            addSectionTitle(document, fonts, "Purchased Products");
            addItemsTable(
                    document,
                    fonts,
                    new String[]{"CBL Code", "Product", "Quantity", "Unit", "Unit Price", "Amount", "Expiry"},
                    new int[]{13, 27, 11, 9, 13, 14, 13},
                    items.stream().map(item -> new String[]{
                            item.getProduct().getCblProductCode(),
                            item.getProduct().getProductName(),
                            formatQuantity(item.getQuantity()),
                            item.getUnit(),
                            formatMoney(item.getUnitPrice()),
                            formatMoney(item.getAmount()),
                            formatDate(item.getExpiryDate())
                    }).toList()
            );

            addTotalsTable(document, fonts, new String[][]{
                    {"Subtotal", formatMoney(invoice.getSubtotal())},
                    {"Discount", formatMoney(invoice.getDiscountAmount())},
                    {"VAT", formatMoney(invoice.getVatAmount())},
                    {"Total Amount", formatMoney(invoice.getTotalAmount())}
            });

            addSignatureFooter(document, fonts);

            document.close();
            return outputStream.toByteArray();

        } catch (DocumentException exception) {
            throw new IllegalStateException("Could not generate the purchase invoice PDF.", exception);
        }
    }

    private Document newDocument() {
        return new Document(PageSize.A4, 36, 36, 36, 36);
    }

    private void addLetterhead(Document document, Fonts fonts, String title, String documentNumber, String date) throws DocumentException {

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{1.2f, 1f});

        PdfPCell companyCell = borderlessCell();
        companyCell.addElement(fonts.paragraph("AAK Agency", TITLE_FONT));
        companyCell.addElement(fonts.paragraph("Distribution Management System", SUBTITLE_FONT));
        header.addCell(companyCell);

        PdfPCell titleCell = borderlessCell();
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph titlePara = fonts.paragraph(title, SECTION_FONT);
        titlePara.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(titlePara);
        Paragraph numberPara = fonts.paragraph("No: " + documentNumber, VALUE_FONT);
        numberPara.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(numberPara);
        Paragraph datePara = fonts.paragraph("Date: " + date, VALUE_FONT);
        datePara.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(datePara);
        header.addCell(titleCell);

        document.add(header);

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(4f);
        document.add(spacer);
    }

    private void addKeyValueGrid(Document document, Fonts fonts, String[][] pairs) throws DocumentException {

        PdfPTable grid = new PdfPTable(4);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(8f);
        grid.setSpacingAfter(12f);

        for (String[] pair : pairs) {
            PdfPCell labelCell = borderlessCell();
            labelCell.addElement(fonts.paragraph(pair[0].toUpperCase(), LABEL_FONT));
            labelCell.addElement(fonts.paragraph(valueOrDash(pair[1]), VALUE_FONT));
            labelCell.setPaddingBottom(8f);
            grid.addCell(labelCell);
        }

        document.add(grid);
    }

    private void addSectionTitle(Document document, Fonts fonts, String title) throws DocumentException {
        Paragraph heading = fonts.paragraph(title, SECTION_FONT);
        heading.setSpacingBefore(6f);
        heading.setSpacingAfter(8f);
        document.add(heading);
    }

    private void addItemsTable(Document document, Fonts fonts, String[] headers, int[] widths, List<String[]> rows) throws DocumentException {

        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        float[] widthFloats = new float[widths.length];
        for (int i = 0; i < widths.length; i++) {
            widthFloats[i] = widths[i];
        }
        table.setWidths(widthFloats);

        for (String header : headers) {
            PdfPCell cell = new PdfPCell(fonts.phrase(header, TABLE_HEADER_FONT));
            cell.setBackgroundColor(TABLE_HEADER_BACKGROUND);
            cell.setPadding(6f);
            table.addCell(cell);
        }
        // A long bill runs over to a second page: repeat the column names there, and never split a product's row.
        table.setHeaderRows(1);
        table.setSplitLate(true);
        table.setSplitRows(false);

        for (String[] row : rows) {
            for (String value : row) {
                PdfPCell cell = new PdfPCell(fonts.phrase(value, TABLE_CELL_FONT));
                cell.setPadding(5f);
                table.addCell(cell);
            }
        }

        if (rows.isEmpty()) {
            PdfPCell emptyCell = new PdfPCell(fonts.phrase("No products on this invoice.", TABLE_CELL_FONT));
            emptyCell.setColspan(headers.length);
            emptyCell.setPadding(10f);
            table.addCell(emptyCell);
        }

        table.setSpacingAfter(12f);
        document.add(table);
    }

    private void addTotalsTable(Document document, Fonts fonts, String[][] rows) throws DocumentException {

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(45);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setWidths(new float[]{1.3f, 1f});
        // Gross, discount, returns, net, paid and balance belong together: all on one page, never split.
        table.setKeepTogether(true);

        for (String[] row : rows) {
            boolean isGrandTotal = row[0].toLowerCase().contains("net amount") || row[0].toLowerCase().contains("total amount");

            PdfPCell labelCell = new PdfPCell(fonts.phrase(row[0], isGrandTotal ? TOTAL_VALUE_FONT : TOTAL_LABEL_FONT));
            labelCell.setBorder(isGrandTotal ? com.lowagie.text.Rectangle.TOP : com.lowagie.text.Rectangle.NO_BORDER);
            labelCell.setPadding(5f);
            table.addCell(labelCell);

            PdfPCell valueCell = new PdfPCell(fonts.phrase(row[1], isGrandTotal ? TOTAL_VALUE_FONT : TOTAL_LABEL_FONT));
            valueCell.setBorder(isGrandTotal ? com.lowagie.text.Rectangle.TOP : com.lowagie.text.Rectangle.NO_BORDER);
            valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            valueCell.setPadding(5f);
            table.addCell(valueCell);
        }

        document.add(table);
    }

    private void addSignatureFooter(Document document, Fonts fonts) throws DocumentException {

        PdfPTable signatures = new PdfPTable(3);
        signatures.setWidthPercentage(100);
        signatures.setSpacingBefore(40f);
        signatures.setKeepTogether(true);

        for (String label : new String[]{"Prepared By", "Checked By", "Customer Signature"}) {
            PdfPCell cell = new PdfPCell(fonts.phrase(label, LABEL_FONT));
            cell.setBorder(com.lowagie.text.Rectangle.TOP);
            cell.setPaddingTop(6f);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            signatures.addCell(cell);
        }

        document.add(signatures);
    }

    private PdfPCell borderlessCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        return cell;
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** "Rs. 1,234.56" - thousands separators and always two decimals. */
    static String formatMoney(BigDecimal amount) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        return "Rs. " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT))
                .format(safeAmount.setScale(2, RoundingMode.HALF_UP));
    }

    /** dd/mm/yyyy, or "-" when there is no date. */
    static String formatDate(LocalDate date) {
        return date == null ? "-" : date.format(DATE_FORMAT);
    }

    private String formatQuantity(BigDecimal quantity) {
        BigDecimal safeQuantity = quantity == null ? BigDecimal.ZERO : quantity;
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT))
                .format(safeQuantity.setScale(2, RoundingMode.HALF_UP));
    }
}
