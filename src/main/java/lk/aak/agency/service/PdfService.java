package lk.aak.agency.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Generates printable PDF copies of sales/purchase invoices (OpenPDF, no external renderer needed). */
@Service
public class PdfService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Color BRAND_COLOR = new Color(0x21, 0x3F, 0x66);
    private static final Color TABLE_HEADER_BACKGROUND = new Color(0xE5, 0xED, 0xF8);

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 20, Font.BOLD, BRAND_COLOR);
    private static final Font SUBTITLE_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, Color.DARK_GRAY);
    private static final Font SECTION_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, BRAND_COLOR);
    private static final Font LABEL_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, Color.GRAY);
    private static final Font VALUE_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, Color.BLACK);
    private static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, BRAND_COLOR);
    private static final Font TABLE_CELL_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font TOTAL_LABEL_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, Color.DARK_GRAY);
    private static final Font TOTAL_VALUE_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, BRAND_COLOR);

    public byte[] generateSalesInvoicePdf(
            SalesInvoice invoice,
            List<SalesInvoiceItem> items,
            BigDecimal paidAmount,
            BigDecimal balance) {

        Customer customer = invoice.getCustomer();

        Document document = newDocument();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, outputStream);
            document.open();

            addLetterhead(document, "SALES INVOICE", invoice.getInvoiceNumber(), invoice.getInvoiceDate().format(DATE_FORMAT));

            addKeyValueGrid(document, new String[][]{
                    {"Customer Code", customer.getCustomerCode()},
                    {"Customer Name", customer.getCustomerName()},
                    {"Area", valueOrDash(customer.getArea())},
                    {"Phone", valueOrDash(customer.getPhone())},
                    {"Sale Type", invoice.getSaleType()},
                    {"Due Date", invoice.getDueDate() != null ? invoice.getDueDate().format(DATE_FORMAT) : "-"},
                    {"Route Code", valueOrDash(invoice.getRouteCode())},
                    {"Payment Status", invoice.getPaymentStatus()}
            });

            addSectionTitle(document, "Sold Products");
            addItemsTable(
                    document,
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

            addTotalsTable(document, new String[][]{
                    {"Gross Amount", formatMoney(invoice.getGrossAmount())},
                    {"Discount", formatMoney(invoice.getDiscountAmount())},
                    {"Returns", formatMoney(invoice.getReturnAmount())},
                    {"Net Amount", formatMoney(invoice.getNetAmount())},
                    {"Paid", formatMoney(paidAmount)},
                    {"Balance", formatMoney(balance)}
            });

            addSignatureFooter(document);

            document.close();
            return outputStream.toByteArray();

        } catch (DocumentException exception) {
            throw new IllegalStateException("Could not generate the sales invoice PDF.", exception);
        }
    }

    public byte[] generatePurchaseInvoicePdf(PurchaseInvoice invoice, List<PurchaseInvoiceItem> items) {

        Document document = newDocument();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, outputStream);
            document.open();

            addLetterhead(document, "PURCHASE INVOICE", invoice.getDocumentNumber(), invoice.getInvoiceDate().format(DATE_FORMAT));

            addKeyValueGrid(document, new String[][]{
                    {"Supplier", invoice.getSupplierName()},
                    {"Tax Invoice No.", valueOrDash(invoice.getTaxInvoiceNumber())},
                    {"PO Number", valueOrDash(invoice.getPoNumber())},
                    {"Delivery Date", invoice.getDeliveryDate() != null ? invoice.getDeliveryDate().format(DATE_FORMAT) : "-"},
                    {"Territory", valueOrDash(invoice.getTerritory())},
                    {"Place of Supply", invoice.getPlaceOfSupply()},
                    {"Payment Method", valueOrDash(invoice.getPaymentMethod())},
                    {"Status", invoice.getStatus()}
            });

            addSectionTitle(document, "Purchased Products");
            addItemsTable(
                    document,
                    new String[]{"CBL Code", "Product", "Quantity", "Unit", "Unit Price", "Amount", "Expiry"},
                    new int[]{13, 27, 11, 9, 13, 14, 13},
                    items.stream().map(item -> new String[]{
                            item.getProduct().getCblProductCode(),
                            item.getProduct().getProductName(),
                            formatQuantity(item.getQuantity()),
                            item.getUnit(),
                            formatMoney(item.getUnitPrice()),
                            formatMoney(item.getAmount()),
                            item.getExpiryDate() != null ? item.getExpiryDate().format(DATE_FORMAT) : "-"
                    }).toList()
            );

            addTotalsTable(document, new String[][]{
                    {"Subtotal", formatMoney(invoice.getSubtotal())},
                    {"Discount", formatMoney(invoice.getDiscountAmount())},
                    {"VAT", formatMoney(invoice.getVatAmount())},
                    {"Total Amount", formatMoney(invoice.getTotalAmount())}
            });

            addSignatureFooter(document);

            document.close();
            return outputStream.toByteArray();

        } catch (DocumentException exception) {
            throw new IllegalStateException("Could not generate the purchase invoice PDF.", exception);
        }
    }

    private Document newDocument() {
        return new Document(PageSize.A4, 36, 36, 36, 36);
    }

    private void addLetterhead(Document document, String title, String documentNumber, String date) throws DocumentException {

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{1.2f, 1f});

        PdfPCell companyCell = borderlessCell();
        companyCell.addElement(new Paragraph("AAK Agency", TITLE_FONT));
        companyCell.addElement(new Paragraph("Distribution Management System", SUBTITLE_FONT));
        header.addCell(companyCell);

        PdfPCell titleCell = borderlessCell();
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph titlePara = new Paragraph(title, SECTION_FONT);
        titlePara.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(titlePara);
        Paragraph numberPara = new Paragraph("No: " + documentNumber, VALUE_FONT);
        numberPara.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(numberPara);
        Paragraph datePara = new Paragraph("Date: " + date, VALUE_FONT);
        datePara.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(datePara);
        header.addCell(titleCell);

        document.add(header);

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(4f);
        document.add(spacer);
    }

    private void addKeyValueGrid(Document document, String[][] pairs) throws DocumentException {

        PdfPTable grid = new PdfPTable(4);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(8f);
        grid.setSpacingAfter(12f);

        for (String[] pair : pairs) {
            PdfPCell labelCell = borderlessCell();
            labelCell.addElement(new Paragraph(pair[0].toUpperCase(), LABEL_FONT));
            labelCell.addElement(new Paragraph(valueOrDash(pair[1]), VALUE_FONT));
            labelCell.setPaddingBottom(8f);
            grid.addCell(labelCell);
        }

        document.add(grid);
    }

    private void addSectionTitle(Document document, String title) throws DocumentException {
        Paragraph heading = new Paragraph(title, SECTION_FONT);
        heading.setSpacingBefore(6f);
        heading.setSpacingAfter(8f);
        document.add(heading);
    }

    private void addItemsTable(Document document, String[] headers, int[] widths, List<String[]> rows) throws DocumentException {

        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        float[] widthFloats = new float[widths.length];
        for (int i = 0; i < widths.length; i++) {
            widthFloats[i] = widths[i];
        }
        table.setWidths(widthFloats);

        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, TABLE_HEADER_FONT));
            cell.setBackgroundColor(TABLE_HEADER_BACKGROUND);
            cell.setPadding(6f);
            table.addCell(cell);
        }

        for (String[] row : rows) {
            for (String value : row) {
                PdfPCell cell = new PdfPCell(new Phrase(value, TABLE_CELL_FONT));
                cell.setPadding(5f);
                table.addCell(cell);
            }
        }

        if (rows.isEmpty()) {
            PdfPCell emptyCell = new PdfPCell(new Phrase("No products on this invoice.", TABLE_CELL_FONT));
            emptyCell.setColspan(headers.length);
            emptyCell.setPadding(10f);
            table.addCell(emptyCell);
        }

        table.setSpacingAfter(12f);
        document.add(table);
    }

    private void addTotalsTable(Document document, String[][] rows) throws DocumentException {

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(45);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setWidths(new float[]{1.3f, 1f});

        for (String[] row : rows) {
            boolean isGrandTotal = row[0].toLowerCase().contains("net amount") || row[0].toLowerCase().contains("total amount");

            PdfPCell labelCell = new PdfPCell(new Phrase(row[0], isGrandTotal ? TOTAL_VALUE_FONT : TOTAL_LABEL_FONT));
            labelCell.setBorder(isGrandTotal ? com.lowagie.text.Rectangle.TOP : com.lowagie.text.Rectangle.NO_BORDER);
            labelCell.setPadding(5f);
            table.addCell(labelCell);

            PdfPCell valueCell = new PdfPCell(new Phrase(row[1], isGrandTotal ? TOTAL_VALUE_FONT : TOTAL_LABEL_FONT));
            valueCell.setBorder(isGrandTotal ? com.lowagie.text.Rectangle.TOP : com.lowagie.text.Rectangle.NO_BORDER);
            valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            valueCell.setPadding(5f);
            table.addCell(valueCell);
        }

        document.add(table);
    }

    private void addSignatureFooter(Document document) throws DocumentException {

        PdfPTable signatures = new PdfPTable(3);
        signatures.setWidthPercentage(100);
        signatures.setSpacingBefore(40f);

        for (String label : new String[]{"Prepared By", "Checked By", "Customer Signature"}) {
            PdfPCell cell = new PdfPCell(new Phrase(label, LABEL_FONT));
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

    private String formatMoney(BigDecimal amount) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        return "Rs. " + safeAmount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String formatQuantity(BigDecimal quantity) {
        BigDecimal safeQuantity = quantity == null ? BigDecimal.ZERO : quantity;
        return safeQuantity.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }
}
