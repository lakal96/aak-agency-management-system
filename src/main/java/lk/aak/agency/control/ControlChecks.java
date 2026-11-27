package lk.aak.agency.control;

import lk.aak.agency.control.ControlReports.RdBill;
import lk.aak.agency.control.ControlReports.RdSales;
import lk.aak.agency.control.ControlReports.StockRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The daily checks: paper (the control book) against what Connect Plus says. Pure arithmetic on the figures given, so every
 * result can be explained and tested. A check is PASS, FAIL, WAITING (the paper or the CBL report is not in yet) or NA.
 */
public final class ControlChecks {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String WAITING = "WAITING";
    public static final String NA = "NA";

    public static final String BILL_LINES = "BILL_LINES";
    public static final String BILLS_VS_CBL = "BILLS_VS_CBL";
    public static final String NET_VS_CBL = "NET_VS_CBL";
    public static final String PAYMENT_VS_CBL = "PAYMENT_VS_CBL";
    public static final String CASH_COUNT = "CASH_COUNT";
    public static final String STOCK = "STOCK";
    public static final String TABLET = "TABLET";
    public static final String SAFE = "SAFE";
    public static final String DAY_END = "DAY_END";

    /** paper = what the book says, comparedWith = what it was compared with ("CBL", "Counted"), compared = that figure. */
    public record Result(String code, String title, String status, String paper, String comparedWith, String compared, String detail) { }

    public record PaperBill(String billNo, BigDecimal value, BigDecimal cashRep, BigDecimal cashDriver, BigDecimal cheque, BigDecimal credit) { }

    public record PaperStock(String product, Integer counted) { }

    public record Paper(boolean vanSales, List<PaperBill> bills, BigDecimal cashCountedRep, BigDecimal cashCountedDriver,
                        List<PaperStock> stock, String tabletMorning, String tabletAfter, String morningStockOk) { }

    private ControlChecks() { }

    public static List<Result> lorry(Paper paper, RdSales rd, List<StockRow> systemStock) {
        List<Result> out = new ArrayList<>();
        out.add(billLines(paper));
        out.add(billsVsCbl(paper, rd));
        out.add(netVsCbl(paper, rd));
        out.add(paymentVsCbl(paper, rd));
        out.add(cashCount(paper));
        out.add(stock(paper, systemStock));
        if (paper.vanSales()) out.add(tablet(paper));
        return out;
    }

    public static List<Result> book(BigDecimal opening, BigDecimal cashCountedAll, BigDecimal banked, BigDecimal paidOut, BigDecimal safeCounted, boolean dayEndDone) {
        List<Result> out = new ArrayList<>();
        if (opening == null && safeCounted == null) {
            out.add(new Result(SAFE, "Cash in the safe", WAITING, "", "Counted", "", "The safe has not been written yet."));
        } else {
            BigDecimal should = z(opening).add(z(cashCountedAll)).subtract(z(banked)).subtract(z(paidOut));
            boolean same = safeCounted != null && eq(should, safeCounted);
            out.add(new Result(SAFE, "Cash in the safe", safeCounted == null ? WAITING : same ? PASS : FAIL, money(should), "Counted", safeCounted == null ? "" : money(safeCounted),
                    safeCounted == null ? "Count the safe." : same ? "" : "Should be " + money(should) + " (opening " + money(z(opening)) + " + cash counted " + money(z(cashCountedAll)) + " - banked " + money(z(banked)) + " - paid out " + money(z(paidOut)) + "); counted " + money(safeCounted) + "."));
        }
        out.add(new Result(DAY_END, "Day End pressed in Connect Plus", dayEndDone ? PASS : WAITING, "", "", "", dayEndDone ? "" : "Not ticked yet."));
        return out;
    }

    public static String title(String code) {
        return switch (code) {
            case BILL_LINES -> "Every bill: cash + cheque + credit = bill value";
            case BILLS_VS_CBL -> "Bills on paper = bills in CBL";
            case NET_VS_CBL -> "Bill values = CBL net";
            case PAYMENT_VS_CBL -> "Payments typed into CBL = paper";
            case CASH_COUNT -> "Cash counted = cash on the bills";
            case STOCK -> "Stock";
            case TABLET -> "Rep's tablet and the morning stock";
            case SAFE -> "Cash in the safe";
            case DAY_END -> "Day End pressed in Connect Plus";
            default -> code;
        };
    }

    // ---------------------------------------------------------------- each check

    static Result billLines(Paper p) {
        String title = title(BILL_LINES);
        List<PaperBill> bills = filled(p);
        if (bills.isEmpty()) return new Result(BILL_LINES, title, WAITING, "", "", "", "No bills written yet.");
        List<String> bad = new ArrayList<>();
        for (PaperBill b : bills) {
            if (b.value() == null) { bad.add(b.billNo() + " has no bill value"); continue; }
            BigDecimal paid = z(b.cashRep()).add(z(b.cashDriver())).add(z(b.cheque())).add(z(b.credit()));
            if (!eq(paid, b.value())) bad.add(b.billNo() + ": value " + money(b.value()) + " but cash + cheque + credit = " + money(paid));
        }
        return new Result(BILL_LINES, title, bad.isEmpty() ? PASS : FAIL, bills.size() + " bills", "", "", join(bad));
    }

    static Result billsVsCbl(Paper p, RdSales rd) {
        String title = title(BILLS_VS_CBL);
        List<PaperBill> bills = filled(p);
        if (rd == null) return new Result(BILLS_VS_CBL, title, WAITING, String.valueOf(bills.size()), "CBL", "", "The CBL RD Sales report is not uploaded yet.");
        Set<String> paper = new LinkedHashSet<>();
        for (PaperBill b : bills) paper.add(b.billNo());
        Set<String> cbl = new LinkedHashSet<>();
        for (RdBill b : rd.bills()) cbl.add(b.serial());
        List<String> onlyPaper = new ArrayList<>(paper); onlyPaper.removeAll(cbl);
        List<String> onlyCbl = new ArrayList<>(cbl); onlyCbl.removeAll(paper);
        List<String> detail = new ArrayList<>();
        if (!onlyPaper.isEmpty()) detail.add("On paper but not in CBL: " + String.join(", ", onlyPaper));
        if (!onlyCbl.isEmpty()) detail.add("In CBL but not on paper: " + String.join(", ", onlyCbl));
        if (paper.size() != bills.size()) detail.add("A bill number is written twice on paper.");
        return new Result(BILLS_VS_CBL, title, detail.isEmpty() ? PASS : FAIL, String.valueOf(bills.size()), "CBL", String.valueOf(rd.bills().size()), join(detail));
    }

    static Result netVsCbl(Paper p, RdSales rd) {
        String title = title(NET_VS_CBL);
        List<PaperBill> bills = filled(p);
        BigDecimal paperTotal = BigDecimal.ZERO;
        for (PaperBill b : bills) paperTotal = paperTotal.add(z(b.value()));
        if (rd == null) return new Result(NET_VS_CBL, title, WAITING, money(paperTotal), "CBL", "", "The CBL RD Sales report is not uploaded yet.");
        Map<String, BigDecimal> cbl = new LinkedHashMap<>();
        for (RdBill b : rd.bills()) cbl.put(b.serial(), b.net());
        List<String> detail = new ArrayList<>();
        for (PaperBill b : bills) {
            BigDecimal net = cbl.get(b.billNo());
            if (net != null && b.value() != null && !eq(net, b.value())) detail.add(b.billNo() + ": paper " + money(b.value()) + ", CBL " + money(net));
        }
        if (!eq(paperTotal, rd.netTotal()) && detail.isEmpty()) detail.add("The totals differ; check which bills are missing (see the bills check).");
        return new Result(NET_VS_CBL, title, detail.isEmpty() && eq(paperTotal, rd.netTotal()) ? PASS : FAIL, money(paperTotal), "CBL", money(rd.netTotal()), join(detail));
    }

    static Result paymentVsCbl(Paper p, RdSales rd) {
        String title = title(PAYMENT_VS_CBL);
        List<PaperBill> bills = filled(p);
        BigDecimal paperCash = BigDecimal.ZERO;
        for (PaperBill b : bills) paperCash = paperCash.add(z(b.cashRep())).add(z(b.cashDriver()));
        if (rd == null) return new Result(PAYMENT_VS_CBL, title, WAITING, money(paperCash), "CBL", "", "The CBL RD Sales report is not uploaded yet.");
        Map<String, RdBill> cbl = new LinkedHashMap<>();
        for (RdBill b : rd.bills()) cbl.put(b.serial(), b);
        List<String> detail = new ArrayList<>();
        BigDecimal cblCash = BigDecimal.ZERO;
        for (RdBill b : rd.bills()) cblCash = cblCash.add(b.cashPaid());
        for (PaperBill b : bills) {
            RdBill c = cbl.get(b.billNo());
            if (c == null) continue;
            BigDecimal cash = z(b.cashRep()).add(z(b.cashDriver()));
            if (!eq(cash, c.cashPaid())) detail.add(b.billNo() + ": cash on paper " + money(cash) + ", in CBL " + money(c.cashPaid()));
            if (z(b.cheque()).signum() > 0 && !c.paymentMode().toLowerCase(Locale.ROOT).contains("cheque")) detail.add(b.billNo() + ": cheque on paper, but CBL shows \"" + c.paymentMode() + "\"");
        }
        return new Result(PAYMENT_VS_CBL, title, detail.isEmpty() ? PASS : FAIL, money(paperCash), "CBL", money(cblCash), join(detail));
    }

    static Result cashCount(Paper p) {
        String title = title(CASH_COUNT);
        BigDecimal rep = BigDecimal.ZERO, driver = BigDecimal.ZERO;
        for (PaperBill b : filled(p)) { rep = rep.add(z(b.cashRep())); driver = driver.add(z(b.cashDriver())); }
        if (p.cashCountedRep() == null && p.cashCountedDriver() == null) {
            return new Result(CASH_COUNT, title, WAITING, money(rep.add(driver)), "Counted", "", "The cash has not been counted yet.");
        }
        List<String> detail = new ArrayList<>();
        BigDecimal counted = z(p.cashCountedRep()).add(z(p.cashCountedDriver()));
        if (p.vanSales()) {
            if (!eq(rep, z(p.cashCountedRep()))) detail.add("Rep: bills say " + money(rep) + ", counted " + money(z(p.cashCountedRep())));
            if (!eq(driver, z(p.cashCountedDriver()))) detail.add("Driver: bills say " + money(driver) + ", counted " + money(z(p.cashCountedDriver())));
        } else if (!eq(rep.add(driver), counted)) {
            detail.add("Bills say " + money(rep.add(driver)) + ", counted " + money(counted));
        }
        return new Result(CASH_COUNT, title, detail.isEmpty() ? PASS : FAIL, money(rep.add(driver)), "Counted", money(counted), join(detail));
    }

    static Result stock(Paper p, List<StockRow> system) {
        String title = p.vanSales() ? "Stock counted on the van = Stock Balance Report" : "Stock on the lorry after Unload is zero";
        if (system == null) return new Result(STOCK, title, WAITING, "", "CBL", "", "The Stock Balance Report is not uploaded yet.");
        List<String> detail = new ArrayList<>();
        if (!p.vanSales()) {
            for (StockRow r : system) if (r.quantity() != 0) detail.add(r.sku() + " " + r.quantity());
            return new Result(STOCK, title, detail.isEmpty() ? PASS : FAIL, "0", "CBL", String.valueOf(system.stream().filter(r -> r.quantity() != 0).count()) + " products with stock",
                    detail.isEmpty() ? "" : "Still on the lorry in CBL: " + join(detail));
        }
        Map<String, Integer> sys = new LinkedHashMap<>();
        for (StockRow r : system) if (r.quantity() != 0) sys.merge(key(r.sku()), r.quantity(), Integer::sum);
        Map<String, String> names = new LinkedHashMap<>();
        for (StockRow r : system) names.putIfAbsent(key(r.sku()), r.sku());
        Set<String> counted = new LinkedHashSet<>();
        int lines = 0;
        for (PaperStock s : p.stock()) {
            if (s.counted() == null || s.product() == null || s.product().isBlank()) continue;
            lines++;
            String k = key(s.product());
            counted.add(k);
            int expected = sys.getOrDefault(k, 0);
            if (expected != s.counted()) detail.add(s.product().trim() + ": counted " + s.counted() + ", system " + expected);
        }
        for (Map.Entry<String, Integer> e : sys.entrySet()) if (!counted.contains(e.getKey())) detail.add(names.get(e.getKey()) + ": not counted, system " + e.getValue());
        if (lines == 0 && sys.isEmpty()) return new Result(STOCK, title, WAITING, "", "CBL", "", "No stock count written yet.");
        if (lines == 0) return new Result(STOCK, title, WAITING, "", "CBL", sys.size() + " products", "No stock count written yet.");
        return new Result(STOCK, title, detail.isEmpty() ? PASS : FAIL, lines + " products counted", "CBL", sys.size() + " products with stock", join(detail));
    }

    static Result tablet(Paper p) {
        String title = title(TABLET);
        String[] answers = {p.tabletMorning(), p.tabletAfter(), p.morningStockOk()};
        String[] names = {"The tablet did not show the stock in the morning", "The tablet did not show the remaining stock after sales", "In the morning the Stock Balance was not last night's count + the Load"};
        List<String> detail = new ArrayList<>();
        boolean blank = false;
        for (int i = 0; i < answers.length; i++) {
            if (answers[i] == null || answers[i].isBlank()) blank = true;
            else if (answers[i].equalsIgnoreCase("NO")) detail.add(names[i]);
        }
        boolean all = answers[0] == null && answers[1] == null && answers[2] == null;
        return new Result(TABLET, title, !detail.isEmpty() ? FAIL : all || blank ? WAITING : PASS, "", "", "", all ? "The three Yes/No answers are not written yet." : join(detail));
    }

    // ---------------------------------------------------------------- helpers

    private static List<PaperBill> filled(Paper p) {
        List<PaperBill> out = new ArrayList<>();
        for (PaperBill b : p.bills()) if (b.billNo() != null && !b.billNo().isBlank()) out.add(b);
        return out;
    }

    private static String key(String name) { return name == null ? "" : name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT); }

    private static String join(List<String> parts) {
        if (parts.size() <= 8) return String.join("; ", parts);
        return String.join("; ", parts.subList(0, 8)) + "; and " + (parts.size() - 8) + " more";
    }

    static BigDecimal z(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    static boolean eq(BigDecimal a, BigDecimal b) {
        return a.setScale(2, RoundingMode.HALF_UP).compareTo(b.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    static String money(BigDecimal v) { return String.format(Locale.ENGLISH, "%,.2f", v); }
}
