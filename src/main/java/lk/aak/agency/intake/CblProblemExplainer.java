package lk.aak.agency.intake;

import java.util.List;
import java.util.Locale;

/**
 * Turns the reader's technical complaints into what an office worker needs to know: what happened, whether anything was imported, and what to do.
 * The technical lines stay available (under "Technical details") for the developer.
 *
 * The category also drives the format-change alarm: FORMAT and NEW_DOCUMENT are things that point at CBL having changed how its documents look
 * (the reader could not find what it always finds), while DATA is about the contents (a pack size that is not known yet, a quantity that
 * disagrees) and SYSTEM is the system's own business (a duplicate being stored at the same moment).
 */
public final class CblProblemExplainer {

    public static final String FORMAT = "FORMAT";
    public static final String NEW_DOCUMENT = "NEW_DOCUMENT";
    public static final String DATA = "DATA";
    public static final String SYSTEM = "SYSTEM";

    public record Explanation(String category, String headline, String whatToDo) {
    }

    private static final String HAND = "Nothing was imported. Open the PDF, check it, and enter it by hand.";
    private static final String HAND_AND_TELL = HAND + " If this keeps happening, CBL may have changed how its documents look: tell the developer.";

    private CblProblemExplainer() {
    }

    /** @param kind INVOICE, CREDIT_NOTES or OTHER (an e-mail the script could not use) */
    public static Explanation explain(String kind, List<String> problems) {
        List<String> all = problems == null ? List.of() : problems;
        String what = "CREDIT_NOTES".equals(kind) ? "credit note file" : "OTHER".equals(kind) ? "e-mail" : "invoice";

        // the most telling complaint decides: a layout problem beats a content problem
        Explanation data = null;
        for (String raw : all) {
            String p = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (p.contains("subject this script does not pick up")) {
                return new Explanation(NEW_DOCUMENT, "CBL sent a kind of document the system does not know yet.",
                        "Nothing was imported. Open the e-mail in Gmail and see what it is. If it matters, enter it by hand, and tell the developer so the system can learn it.");
            }
            if (p.contains("no pdf is attached")) {
                return new Explanation(DATA, "CBL sent an e-mail with no PDF, so there is nothing to import.", "Look at the e-mail in Gmail. If it should have had an invoice or credit note, ask CBL to send it again.");
            }
            if (p.contains("not a pdf file") || p.contains("could not be opened as a pdf")) {
                return new Explanation(SYSTEM, "The attachment could not be opened as a PDF.", "Nothing was imported. Open the e-mail in Gmail. If the file is damaged, ask CBL to send it again.");
            }
            if (p.contains("refused to store") || p.contains("same moment") || p.contains("already approved")) {
                return new Explanation(SYSTEM, "The system could not save this one just now.", "Nothing was changed. It will be tried again by itself; if it is still here tomorrow, tell the developer.");
            }
            if (p.contains("holds") && p.contains("credit notes but only")) {
                return new Explanation(FORMAT, "The file has more credit notes than the system could read, so it imported none of them.", HAND_AND_TELL);
            }
            if (p.contains("layout has changed") || p.contains("table headings are not")) {
                return new Explanation(FORMAT, "CBL's credit note looks different from the usual one.", HAND_AND_TELL);
            }
            if (p.contains("could not be read") || p.contains("was not found") || p.contains("no product rows") || p.contains("does not look like")
                    || p.contains("is not a credit note") || p.contains("no amount was found") || p.contains("not found")) {
                return new Explanation(FORMAT, "The system could not read this " + what + " the usual way.", HAND_AND_TELL);
            }
            if (p.contains("is not one i know")) {
                return new Explanation(FORMAT, "A line uses a unit the system does not know.", HAND_AND_TELL);
            }
            if (p.contains("add up to") || p.contains("is not the total value of supply") || p.contains("is far from the amount")) {
                return new Explanation(FORMAT, "The figures on the " + what + " do not add up the way the system read them, so it did not trust them.", HAND_AND_TELL);
            }
            if (p.contains("master cartons")) {
                data = new Explanation(DATA, "A line is counted in cartons and the pack size is not known, so the pieces cannot be worked out.",
                        "Nothing was imported. Add the pack size to that product's name in Products (like ...X12EA) and the system retries by itself, or enter the invoice by hand.");
            } else if (p.contains("do not match the invoiced quantity") || p.contains("not a whole number of pieces")) {
                data = new Explanation(DATA, "A line's boxes and quantity do not agree.", "Nothing was imported. Check the PDF for a mistake on CBL's side (ask CBL if so), then enter the invoice by hand.");
            }
        }
        if (data != null) {
            return data;
        }
        return new Explanation(FORMAT, "The system could not read this " + what + ".", HAND_AND_TELL);
    }

    /** True when this kind of trouble can point at CBL having changed its documents. */
    public static boolean pointsAtFormatChange(String category) {
        return FORMAT.equals(category) || NEW_DOCUMENT.equals(category);
    }
}
