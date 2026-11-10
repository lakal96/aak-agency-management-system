package lk.aak.agency.intake;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Reads the PDF product invoice CBL e-mails ("INVOICE", Tax Invoice Number ..., product rows with a code, weight,
 * boxes, sold quantity in DZ/MC, unit price and amount).
 *
 * It never guesses: a row it cannot read exactly, a unit it does not know, quantities that do not agree with the
 * pack size in the product name, or totals that do not add up all end up in {@link CblDocument#problems()} and the
 * caller must refuse the document.
 */
@Component
public class CblInvoiceParser {

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private static final Pattern TAX_INVOICE = Pattern.compile("Tax Invoice Number:\\s*(\\S+)");
    private static final Pattern INVOICE_DATE = Pattern.compile("Date of Invoice:\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern SUPPLY_DATE = Pattern.compile("Date of Supply:\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern PO = Pattern.compile("P\\.O Number:\\s*(\\S+)");
    private static final Pattern DELIVERY_NOTE = Pattern.compile("Delivery Note:\\s*([0-9 ]+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern TERRITORY = Pattern.compile("Territory:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern PLACE = Pattern.compile("Place of Supply:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern PAYMENT = Pattern.compile("Mode of Payments\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern TOTAL_ORDER = Pattern.compile("Total Order Value\\s+([\\d,]+\\.\\d{2})");
    private static final Pattern DISCOUNT = Pattern.compile("Less Discount\\s+([\\d,]+\\.\\d{2})");
    private static final Pattern SUPPLY_TOTAL = Pattern.compile("Total Value of Supply\\s*:\\s*([\\d,]+\\.\\d{2})");
    private static final Pattern INCL_VAT = Pattern.compile("Total Amount including VAT\\s*:\\s*([\\d,]+\\.\\d{2})");

    /** CHS10746 | CHOCOLA MILK 20GX24DZ - SACMI | 20G | 10.00 | 240.00 | DZ | 503.82 | 120,916.80 */
    private static final Pattern ROW = Pattern.compile(
            "^([A-Z]{2,3}\\d{5,6})\\s+(.+?)\\s+(\\d+(?:\\.\\d+)?\\s?(?:KG|G))\\s+(\\d+\\.\\d{2})\\s+(\\d+\\.\\d{2})\\s+([A-Z]{2,3})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$");
    private static final Pattern ROW_START = Pattern.compile("^[A-Z]{2,3}\\d{5,6}\\s");
    private static final Pattern WRAPPED_DESCRIPTION = Pattern.compile("^[0-9A-Za-z.\\-/&() ]+$");
    private static final Pattern NOT_DESCRIPTION = Pattern.compile(
            "^(Total|Less|Page|Mode|Received|Terms|VAT|INVOICE|Date|P\\.O|Delivery|Supplier|Telephone|Address|Territory|Email|Additional|Product|Code|Place|Purchaser|Net|Sold|Unit|Amount|Rs\\.|\\d{2}[./]\\d{2}[./]\\d{4})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PACK = Pattern.compile("X\\s*(\\d+)\\s*(DZ|EA)\\b", Pattern.CASE_INSENSITIVE);

    public CblDocument parse(byte[] pdf) {
        return parse(pdf, code -> null);
    }

    /**
     * @param productNameByCode what the AAK product list calls a product (null when unknown): used to find a pack size
     *                          that the invoice's own product name does not show
     */
    public CblDocument parse(byte[] pdf, Function<String, String> productNameByCode) {

        String text;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        } catch (IOException | RuntimeException exception) {
            return unreadable("The file could not be opened as a PDF.");
        }
        return parseText(text, productNameByCode);
    }

    /** The reading rules, on the extracted text (so they can be tested without a PDF). */
    public CblDocument parseText(String text) {
        return parseText(text, code -> null);
    }

    public CblDocument parseText(String text, Function<String, String> productNameByCode) {

        if (!text.contains("Tax Invoice Number") || !text.contains("Description of Goods")) {
            return unreadable("This does not look like a CBL product invoice (no 'Tax Invoice Number' / product table).");
        }

        List<String> problems = new ArrayList<>();
        String taxNumber = first(TAX_INVOICE, text);
        LocalDate invoiceDate = date(first(INVOICE_DATE, text));
        LocalDate supplyDate = date(first(SUPPLY_DATE, text));
        if (taxNumber == null) problems.add("The Tax Invoice Number was not found.");
        if (invoiceDate == null) problems.add("The invoice date was not found or is not a date.");

        BigDecimal totalOrder = money(first(TOTAL_ORDER, text));
        BigDecimal discount = money(first(DISCOUNT, text));
        BigDecimal supplyTotal = money(first(SUPPLY_TOTAL, text));
        BigDecimal inclVat = money(first(INCL_VAT, text));
        if (totalOrder == null) problems.add("'Total Order Value' was not found.");
        if (supplyTotal == null) problems.add("'Total Value of Supply' was not found.");
        if (inclVat == null) problems.add("'Total Amount including VAT' was not found.");
        if (discount == null) discount = BigDecimal.ZERO;

        // the header block repeats on every page: read each row once, in order
        List<CblDocument.Line> lines = new ArrayList<>();
        String[] rows = text.split("\\R");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (!ROW_START.matcher(row).find()) continue;

            Matcher m = ROW.matcher(row);
            if (!m.matches()) {
                problems.add("A product row could not be read: \"" + row + "\"");
                continue;
            }

            // a long description wraps onto the next line(s) ("... PACK" / "240GX12EA"): they belong to this row
            StringBuilder description = new StringBuilder(m.group(2));
            int taken = 0;
            while (taken < 3 && i + 1 + taken < rows.length) {
                String next = rows[i + 1 + taken].trim();
                if (!isContinuation(next)) break;
                description.append(' ').append(next);
                taken++;
            }
            i += taken;
            lines.add(toLine(m, description.toString(), problems, productNameByCode));
        }

        // An invoice with no product rows is only "empty" (nothing to import) when every total on it is zero too;
        // no rows but money on the invoice means rows were missed, which is a refusal.
        boolean emptyInvoice = lines.isEmpty() && isZero(totalOrder) && isZero(supplyTotal) && isZero(inclVat) && isZero(discount);
        if (lines.isEmpty() && !emptyInvoice) problems.add("No product rows were found.");

        // the numbers must agree with each other, or the document is refused
        BigDecimal sum = lines.stream().map(CblDocument.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalOrder != null && sum.subtract(totalOrder).abs().compareTo(new BigDecimal("0.01")) > 0) {
            problems.add("The product rows add up to " + sum + " but the invoice says the Total Order Value is " + totalOrder + ".");
        }
        if (totalOrder != null && supplyTotal != null
                && totalOrder.subtract(discount).subtract(supplyTotal).abs().compareTo(new BigDecimal("0.01")) > 0) {
            problems.add("Total Order Value " + totalOrder + " less discount " + discount + " is not the Total Value of Supply " + supplyTotal + ".");
        }

        CblDocument.Invoice invoice = new CblDocument.Invoice(
                taxNumber, invoiceDate, supplyDate, first(PO, text), clean(first(DELIVERY_NOTE, text)),
                first(TERRITORY, text), first(PLACE, text), first(PAYMENT, text),
                totalOrder, discount, supplyTotal, inclVat, List.copyOf(lines));
        return new CblDocument(CblDocument.Kind.INVOICE, invoice, List.copyOf(problems));
    }

    private CblDocument.Line toLine(Matcher m, String rawDescription, List<String> problems, Function<String, String> productNameByCode) {

        String code = m.group(1);
        String description = rawDescription.replaceAll("\\s+", " ").trim();
        String weight = m.group(3).replaceAll("\\s", "");
        BigDecimal boxes = new BigDecimal(m.group(4));
        BigDecimal quantity = new BigDecimal(m.group(5));
        String unit = m.group(6);
        BigDecimal unitPrice = money(m.group(7));
        BigDecimal amount = money(m.group(8));

        // The pack size (X24DZ = 24 dozen per box, X12EA = 12 pieces per box): from the invoice's product name, or - when the
        // invoice does not show it - from what the AAK product list calls this code.
        Matcher pack = PACK.matcher(description);
        boolean packOnInvoice = pack.find();
        if (!packOnInvoice) {
            String known = productNameByCode == null ? null : productNameByCode.apply(code);
            Matcher fromList = known == null ? null : PACK.matcher(known);
            if (fromList != null && fromList.find()) pack = fromList;
            else pack = null;
        }
        String packText = pack == null ? null : pack.group(1) + pack.group(2).toUpperCase();
        BigDecimal piecesPerBox = pack == null ? null
                : new BigDecimal(pack.group(1)).multiply("DZ".equalsIgnoreCase(pack.group(2)) ? TWELVE : BigDecimal.ONE);

        BigDecimal pieces = null;
        if ("DZ".equals(unit)) {
            // A dozen is always 12 pieces, so a line sold in dozens needs no pack size at all.
            BigDecimal exact = quantity.multiply(TWELVE);
            if (exact.stripTrailingZeros().scale() > 0) {
                problems.add(code + ": " + quantity + " DZ is not a whole number of pieces.");
            } else {
                pieces = exact.setScale(0, java.math.RoundingMode.UNNECESSARY);
            }
            // When the pack size is known it is a cross-check on the figures. CBL prints the number of boxes rounded to 2 decimals
            // (3.38 boxes of 16DZ for exactly 54 DZ), so allow for that rounding - and refuse a real disagreement.
            if (pieces != null && piecesPerBox != null) {
                BigDecimal perBoxInDozens = piecesPerBox.divide(TWELVE, 6, java.math.RoundingMode.HALF_UP);
                BigDecimal expectedQuantity = boxes.multiply(perBoxInDozens);
                BigDecimal allowed = perBoxInDozens.multiply(new BigDecimal("0.005")).add(new BigDecimal("0.01"));
                if (quantity.subtract(expectedQuantity).abs().compareTo(allowed) > 0) {
                    problems.add(code + ": " + boxes + " boxes of " + packText + " do not match the invoiced quantity " + quantity + " " + unit + ".");
                    pieces = null;
                }
            }
        } else if ("MC".equals(unit)) {
            // Master cartons: the pieces are boxes x the pack size, so here the pack size is really needed.
            if (piecesPerBox == null) {
                problems.add(code + ": this line is counted in master cartons (MC), but its pack size (like X12EA) is neither in the product name on the invoice "
                        + "nor in your product list, so the pieces cannot be worked out. Add the pack size to the product's name in Products, or enter this invoice by hand.");
            } else if (quantity.subtract(boxes).abs().compareTo(new BigDecimal("0.01")) > 0) {
                problems.add(code + ": " + boxes + " boxes of " + packText + " do not match the invoiced quantity " + quantity + " " + unit + ".");
            } else {
                pieces = boxes.multiply(piecesPerBox);
            }
        } else {
            problems.add(code + ": the unit \"" + unit + "\" is not one I know (DZ or MC).");
        }

        // CBL prints a rounded unit price, so quantity x price may differ from the amount by a little - but not by much
        BigDecimal tolerance = quantity.multiply(new BigDecimal("0.006")).add(new BigDecimal("0.02"));
        if (unitPrice != null && amount != null && quantity.multiply(unitPrice).subtract(amount).abs().compareTo(tolerance) > 0) {
            problems.add(code + ": " + quantity + " x " + unitPrice + " is far from the amount " + amount + ".");
        }
        return new CblDocument.Line(code, description, weight, boxes, quantity, unit, unitPrice, amount, pieces);
    }

    /** Words of a wrapped description - not another row, not a header or footer line of the invoice. */
    private static boolean isContinuation(String line) {
        return !line.isEmpty()
                && line.length() <= 40
                && WRAPPED_DESCRIPTION.matcher(line).matches()
                && !ROW_START.matcher(line).find()
                && !NOT_DESCRIPTION.matcher(line).find();
    }

    private static CblDocument unreadable(String problem) {
        return new CblDocument(CblDocument.Kind.UNKNOWN, null, List.of(problem));
    }

    private static boolean isZero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String clean(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value, US_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal money(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
