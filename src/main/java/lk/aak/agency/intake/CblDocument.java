package lk.aak.agency.intake;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What was read from a PDF that CBL (or Plenty Foods) e-mailed: either a product invoice or credit notes. */
public record CblDocument(
        Kind kind,
        Invoice invoice,
        List<String> problems,
        List<String> layoutWarnings) {

    public CblDocument(Kind kind, Invoice invoice, List<String> problems) {
        this(kind, invoice, problems, List.of());
    }

    public enum Kind { INVOICE, UNKNOWN }

    public boolean readable() {
        return problems.isEmpty();
    }

    public record Invoice(
            String taxInvoiceNumber,
            LocalDate invoiceDate,
            LocalDate supplyDate,
            String poNumber,
            String deliveryNotes,
            String territory,
            String placeOfSupply,
            String paymentMode,
            BigDecimal totalOrderValue,
            BigDecimal discount,
            BigDecimal totalValueOfSupply,
            BigDecimal totalIncludingVat,
            List<Line> lines) {
    }

    /**
     * One product row of the invoice. `pieces` is what goes into stock; the other figures are CBL's own.
     * `description` includes a wrapped second line ("... PACK" + "240GX12EA").
     */
    public record Line(
            String code,
            String description,
            String weight,
            BigDecimal boxes,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal amount,
            BigDecimal pieces) {
    }
}
