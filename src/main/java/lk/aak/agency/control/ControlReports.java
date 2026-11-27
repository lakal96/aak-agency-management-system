package lk.aak.agency.control;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the reports staff download from Connect Plus (Excel, and CSV for the stock balance). Anything unexpected becomes a
 * {@link ReportProblem} with a plain-word message, so a changed layout is reported instead of being read wrongly.
 */
public final class ControlReports {

    private static final DateTimeFormatter CBL_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Pattern DATE_CELL = Pattern.compile("Date\\s*:\\s*(\\d{2}-\\d{2}-\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern VEHICLE_CELL = Pattern.compile("CBL Vehicle\\s*:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    private ControlReports() { }

    public static class ReportProblem extends RuntimeException {
        public ReportProblem(String message) { super(message); }
    }

    public record RdBill(String serial, String outlet, BigDecimal net, String paymentMode, BigDecimal cashPaid) { }

    public record RdSales(LocalDate date, String vehicle, List<RdBill> bills, BigDecimal netTotal) { }

    public record StockRow(String sku, int quantity) { }

    // ---------------------------------------------------------------- RD Sales & Payments (Excel from RD Sales > RD Sales Entry)

    public static RdSales readRdSales(byte[] data, String fileName) {
        List<String[]> rows = rowsOf(data, fileName, "RD Sales Entry");
        LocalDate date = null;
        String vehicle = null;
        int header = -1;
        for (int i = 0; i < rows.size(); i++) {
            for (String cell : rows.get(i)) {
                Matcher d = DATE_CELL.matcher(cell);
                if (date == null && d.find()) {
                    try { date = LocalDate.parse(d.group(1), CBL_DATE); } catch (DateTimeParseException ignored) { }
                }
                Matcher v = VEHICLE_CELL.matcher(cell);
                if (vehicle == null && v.find()) vehicle = v.group(1).trim();
            }
            if (header < 0 && rows.get(i).length > 0 && "Serial No".equalsIgnoreCase(rows.get(i)[0].trim())) header = i;
        }
        if (header < 0) throw new ReportProblem("This does not look like the RD Sales & Payments report: the \"Serial No\" column is missing. Download it again with the Excel button on RD Sales Entry.");
        if (date == null || vehicle == null) throw new ReportProblem("The date or the vehicle is missing at the top of the report. Download it again with the Excel button on RD Sales Entry after pressing Get RD Sales & Payments.");
        String[] heads = rows.get(header);
        int serial = col(heads, "Serial No"), outlet = col(heads, "Outlet Name"), net = col(heads, "Net Amount"),
                mode = col(heads, "Payment Mode"), cash = col(heads, "Cash Amt Paid");
        if (serial < 0 || net < 0 || mode < 0 || cash < 0) {
            throw new ReportProblem("The columns of the RD Sales report have changed (needed: Serial No, Net Amount, Payment Mode, Cash Amt Paid). Tell the owner before using this report.");
        }
        List<RdBill> bills = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = header + 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String first = at(r, 0).trim();
            if (first.isEmpty() || first.equalsIgnoreCase("Total") || first.startsWith("©")) break;
            BigDecimal netAmount = money(at(r, net), "Net Amount of bill " + first);
            bills.add(new RdBill(first, outlet < 0 ? "" : at(r, outlet).trim(), netAmount, at(r, mode).trim(), money(at(r, cash), "Cash Amt Paid of bill " + first)));
            total = total.add(netAmount);
        }
        return new RdSales(date, vehicle, bills, total.setScale(2, RoundingMode.HALF_UP));
    }

    // ---------------------------------------------------------------- Stock Balance Report (CSV or Excel from Stocks > Stock Balance Report)

    public static List<StockRow> readStockBalance(byte[] data, String fileName) {
        List<String[]> rows = rowsOf(data, fileName, "Stock Balance Report");
        int header = -1;
        for (int i = 0; i < rows.size() && header < 0; i++) {
            if (col(rows.get(i), "SKU") >= 0 && col(rows.get(i), "Quantity") >= 0) header = i;
        }
        if (header < 0) throw new ReportProblem("This does not look like the Stock Balance Report: the \"SKU\" and \"Quantity\" columns are missing. Download it again from Stocks > Stock Balance Report (CSV or Excel button).");
        int sku = col(rows.get(header), "SKU"), qty = col(rows.get(header), "Quantity");
        List<StockRow> out = new ArrayList<>();
        for (int i = header + 1; i < rows.size(); i++) {
            // the Excel export keeps the double spaces of the product names, the CSV does not: read both the same way
            String name = at(rows.get(i), sku).trim().replaceAll("\\s+", " ");
            if (name.isEmpty() || name.startsWith("©")) break;
            BigDecimal q = money(at(rows.get(i), qty), "Quantity of " + name);
            if (q.stripTrailingZeros().scale() > 0) throw new ReportProblem("The quantity of \"" + name + "\" is not a whole number (" + q.toPlainString() + ").");
            out.add(new StockRow(name, q.intValueExact()));
        }
        return out;
    }

    // ---------------------------------------------------------------- reading cells

    private static List<String[]> rowsOf(byte[] data, String fileName, String where) {
        if (data == null || data.length == 0) throw new ReportProblem("The file is empty.");
        boolean zip = data.length > 3 && data[0] == 'P' && data[1] == 'K';
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") || (data.length > 4 && new String(data, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF"))) {
            throw new ReportProblem("A PDF cannot be read. Use the Excel button" + (where.startsWith("Stock") ? " (or the CSV button)" : "") + " on " + where + " in Connect Plus.");
        }
        return zip ? excelRows(data) : csvRows(new String(data, StandardCharsets.UTF_8));
    }

    private static List<String[]> excelRows(byte[] data) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            List<String[]> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) { rows.add(new String[0]); continue; }
                int last = Math.max(row.getLastCellNum(), 0);
                String[] cells = new String[last];
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    cells[c] = cell == null ? "" : formatter.formatCellValue(cell).trim();
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException exception) {
            throw new ReportProblem("The Excel file could not be opened. Download it again from Connect Plus and do not change it.");
        }
    }

    /** CSV with quotes and commas inside quotes. */
    static List<String[]> csvRows(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { cells.add(cell.toString().trim()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cells.add(cell.toString().trim()); cell.setLength(0);
                rows.add(cells.toArray(new String[0])); cells = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !cells.isEmpty()) { cells.add(cell.toString().trim()); rows.add(cells.toArray(new String[0])); }
        if (!rows.isEmpty() && rows.get(0).length > 0 && rows.get(0)[0].startsWith("﻿")) rows.get(0)[0] = rows.get(0)[0].substring(1);
        return rows;
    }

    private static int col(String[] heads, String name) {
        for (int i = 0; i < heads.length; i++) if (heads[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static String at(String[] row, int index) { return index >= 0 && index < row.length ? row[index] : ""; }

    private static BigDecimal money(String text, String what) {
        String clean = text == null ? "" : text.replace(",", "").trim();
        if (clean.isEmpty()) return BigDecimal.ZERO.setScale(2);
        try {
            return new BigDecimal(clean).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            throw new ReportProblem("\"" + text + "\" is not a number (" + what + ").");
        }
    }
}
