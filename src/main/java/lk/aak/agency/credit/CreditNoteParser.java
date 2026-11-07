package lk.aak.agency.credit;

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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the credit notes a supplier e-mails ("Credit Note", Doc.No, Date, Ref.Inv, Remarks and Amount). One PDF can hold
 * several notes, one per page. Like the invoice reader it never guesses: a page it cannot read exactly makes the whole
 * file a refusal with the reason.
 */
@Component
public class CreditNoteParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern DOC_NO = Pattern.compile("Doc\\.No\\.?\\s*:\\s*(\\S+)");
    private static final Pattern NOTE_DATE = Pattern.compile("(?<![A-Za-z.])Date\\s*:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern REF_NO = Pattern.compile("Ref\\.Inv\\.No:[ \\t]*(\\S*)");
    private static final Pattern REF_DATE = Pattern.compile("Ref\\.Inv\\.Date:[ \\t]*(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern AMOUNT_LINE = Pattern.compile("^(.+?)\\s+([\\d,]+\\.\\d{2})\\s*$");

    public record Note(String documentNumber, String issuer, LocalDate date, String refInvoiceNumber,
                       LocalDate refInvoiceDate, String remarks, BigDecimal amount) {
    }

    public record Parsed(List<Note> notes, List<String> problems) {
    }

    /** Empty when the PDF is not a credit note document at all (the caller then tries it as an invoice). */
    public Optional<Parsed> parse(byte[] pdf) {

        List<String> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        if (pages.stream().noneMatch(CreditNoteParser::looksLikeCreditNote)) {
            return Optional.empty();
        }
        return Optional.of(parsePages(pages));
    }

    /** The reading rules, on the text of each page (so they can be tested without a PDF). */
    public Parsed parsePages(List<String> pages) {

        List<Note> notes = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < pages.size(); i++) {
            String text = pages.get(i);
            if (text.isBlank()) continue;
            if (!looksLikeCreditNote(text)) {
                problems.add("Page " + (i + 1) + " is not a credit note.");
                continue;
            }
            parsePage(i + 1, text, notes, problems);
        }
        if (notes.isEmpty() && problems.isEmpty()) {
            problems.add("No credit note was found in this file.");
        }
        return new Parsed(List.copyOf(notes), List.copyOf(problems));
    }

    static boolean looksLikeCreditNote(String text) {
        return text.contains("Credit Note") && DOC_NO.matcher(text).find();
    }

    private void parsePage(int pageNumber, String text, List<Note> notes, List<String> problems) {

        String where = "Page " + pageNumber + ": ";
        String number = first(DOC_NO, text);
        LocalDate date = date(first(NOTE_DATE, text));
        if (number == null) problems.add(where + "the document number (Doc.No) was not found.");
        if (date == null) problems.add(where + "the date was not found.");

        // the lines between "Remarks ... Amount Rs." and the closing sentence: description + amount
        List<String> remarks = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        boolean inTable = false;
        boolean closed = false;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("Remarks")) { inTable = true; continue; }
            if (!inTable || line.isEmpty()) continue;
            if (line.toLowerCase().startsWith("this is a system generated")) { closed = true; break; }
            Matcher m = AMOUNT_LINE.matcher(line);
            if (m.matches()) {
                remarks.add(m.group(1).trim());
                amount = amount.add(new BigDecimal(m.group(2).replace(",", "")));
            } else {
                problems.add(where + "a line could not be read: \"" + line + "\"");
            }
        }
        if (!inTable || !closed) problems.add(where + "the remarks / amount table was not found.");
        if (amount.signum() <= 0) problems.add(where + "no amount was found.");
        if (number == null || date == null || amount.signum() <= 0 || !problems.stream().noneMatch(p -> p.startsWith(where))) return;

        String firstLine = text.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("Supplier");
        String issuer = firstLine.replaceAll("\\.$", "");
        String refNo = first(REF_NO, text);
        notes.add(new Note(number, issuer, date, refNo == null || refNo.isBlank() ? null : refNo, date(first(REF_DATE, text)),
                String.join("; ", remarks), amount));
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
