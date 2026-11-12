package lk.aak.agency.intake;

import java.util.ArrayList;
import java.util.List;

/**
 * A second pair of eyes on the reader: CBL's invoices have always carried the same headings. When a PDF still reads (the rows add up to the printed
 * total) but one of those headings has gone or changed, CBL has probably changed the layout, and the reader may be taking a figure from the wrong
 * place without the totals noticing. Such an invoice is still imported as a draft, but marked, and a person is told to check every line.
 */
final class CblLayoutCheck {

    /** What every CBL product invoice shows, as it appears in the extracted text (spaces and line breaks counted as one space). */
    private static final List<String> INVOICE_HEADINGS = List.of(
            "Tax Invoice Number", "Description of Goods or Services", "Unit Price", "Amount Including VAT",
            "Total Order Value", "Total Value of Supply", "Total Amount including VAT");

    private CblLayoutCheck() {
    }

    /** How many words may sit between the words of one heading: a narrow column wraps "Amount Including VAT" onto three lines, and the text of the other columns lands between them. */
    private static final int WINDOW = 40;

    /** The usual headings that are missing from this invoice's text (empty when the layout is the usual one). */
    static List<String> missingInvoiceHeadings(String text) {
        // words without the punctuation stuck to them ("Number:" is "Number")
        String[] tokens = java.util.Arrays.stream((text == null ? "" : text).trim().split("\\s+"))
                .map(token -> token.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "")).toArray(String[]::new);
        List<String> missing = new ArrayList<>();
        for (String heading : INVOICE_HEADINGS) {
            if (!containsInOrder(tokens, heading.split(" "))) {
                missing.add(heading);
            }
        }
        return missing;
    }

    /** True when the heading's words appear in that order, close together (not necessarily next to each other). */
    private static boolean containsInOrder(String[] tokens, String[] words) {
        for (int start = 0; start < tokens.length; start++) {
            if (!tokens[start].equalsIgnoreCase(words[0])) {
                continue;
            }
            int at = start;
            boolean all = true;
            for (int w = 1; w < words.length && all; w++) {
                int found = -1;
                for (int i = at + 1; i < tokens.length && i <= at + WINDOW; i++) {
                    if (tokens[i].equalsIgnoreCase(words[w])) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    all = false;
                } else {
                    at = found;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    static List<String> invoiceWarnings(String text) {
        List<String> missing = missingInvoiceHeadings(text);
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of("The usual heading" + (missing.size() == 1 ? " " : "s ") + String.join(", ", missing.stream().map(h -> "\"" + h + "\"").toList())
                + (missing.size() == 1 ? " is" : " are") + " not on this invoice, so CBL may have changed its layout.");
    }
}
