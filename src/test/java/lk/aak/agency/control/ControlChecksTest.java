package lk.aak.agency.control;

import lk.aak.agency.control.ControlChecks.PaperBill;
import lk.aak.agency.control.ControlChecks.PaperStock;
import lk.aak.agency.control.ControlChecks.Result;
import lk.aak.agency.control.ControlReports.RdBill;
import lk.aak.agency.control.ControlReports.RdSales;
import lk.aak.agency.control.ControlReports.StockRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks are arithmetic on the figures: each one passes, fails or waits, and says why in words. */
class ControlChecksTest {

    private static BigDecimal d(String v) { return v == null ? null : new BigDecimal(v); }

    private static PaperBill bill(String no, String value, String cashRep, String cashDriver, String cheque, String credit) {
        return new PaperBill(no, d(value), d(cashRep), d(cashDriver), d(cheque), d(credit));
    }

    private static ControlChecks.Paper paper(boolean van, List<PaperBill> bills) {
        return new ControlChecks.Paper(van, bills, null, null, List.of(), null, null, null);
    }

    private static RdSales rd(RdBill... bills) {
        BigDecimal total = BigDecimal.ZERO;
        for (RdBill b : bills) total = total.add(b.net());
        return new RdSales(LocalDate.of(2026, 11, 20), "MAKOLARTM-A", List.of(bills), total);
    }

    private static RdBill cbl(String no, String net, String mode, String cash) { return new RdBill(no, "SHOP", d(net), mode, d(cash)); }

    private static Result of(List<Result> results, String code) { return results.stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow(); }

    // ---------------------------------------------------------------- bills

    @Test
    void everyBillMustAddUpFromCashChequeAndCredit() {
        var ok = ControlChecks.billLines(paper(false, List.of(bill("1", "1000.00", null, "400.00", "100.00", "500.00"))));
        assertThat(ok.status()).isEqualTo("PASS");

        var bad = ControlChecks.billLines(paper(false, List.of(bill("1", "1000.00", null, "400.00", null, "500.00"), bill("2", "50.00", null, "50.00", null, null))));
        assertThat(bad.status()).isEqualTo("FAIL");
        assertThat(bad.detail()).contains("1:").contains("900.00").doesNotContain("2:");

        assertThat(ControlChecks.billLines(paper(false, List.of())).status()).isEqualTo("WAITING");
    }

    @Test
    void theBillNumbersOnPaperMustBeTheBillNumbersInCbl() {
        var paper = paper(false, List.of(bill("1", "10", null, "10", null, null), bill("2", "20", null, "20", null, null)));
        assertThat(ControlChecks.billsVsCbl(paper, rd(cbl("1", "10", "Cash", "10"), cbl("2", "20", "Cash", "20"))).status()).isEqualTo("PASS");

        var r = ControlChecks.billsVsCbl(paper, rd(cbl("1", "10", "Cash", "10"), cbl("3", "30", "Cash", "30")));
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("On paper but not in CBL: 2").contains("In CBL but not on paper: 3");

        var waiting = ControlChecks.billsVsCbl(paper, null);
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(waiting.detail()).contains("not uploaded");
    }

    @Test
    void theValueOfEachBillMustBeTheNetInCbl() {
        var paper = paper(false, List.of(bill("1", "100.00", null, "100.00", null, null), bill("2", "200.00", null, "200.00", null, null)));
        assertThat(ControlChecks.netVsCbl(paper, rd(cbl("1", "100.00", "Cash", "100.00"), cbl("2", "200.00", "Cash", "200.00"))).status()).isEqualTo("PASS");

        var r = ControlChecks.netVsCbl(paper, rd(cbl("1", "100.00", "Cash", "100.00"), cbl("2", "250.00", "Cash", "250.00")));
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("2: paper 200.00, CBL 250.00");
        assertThat(r.paper()).isEqualTo("300.00");
        assertThat(r.compared()).isEqualTo("350.00");
    }

    @Test
    void paymentsTypedIntoCblMustBeThePaymentsOnPaper() {
        var paper = paper(false, List.of(bill("1", "100.00", null, "60.00", null, "40.00"), bill("2", "500.00", null, null, "500.00", null)));
        // CBL still shows both bills as unpaid credit: nothing has been typed in yet
        var notTyped = ControlChecks.paymentVsCbl(paper, rd(cbl("1", "100.00", "Credit", "0.00"), cbl("2", "500.00", "Credit", "0.00")));
        assertThat(notTyped.status()).isEqualTo("FAIL");
        assertThat(notTyped.detail()).contains("1: cash on paper 60.00, in CBL 0.00").contains("2: cheque on paper, but CBL shows \"Credit\"");

        var typed = ControlChecks.paymentVsCbl(paper, rd(cbl("1", "100.00", "Cash", "60.00"), cbl("2", "500.00", "Cheque", "0.00")));
        assertThat(typed.status()).isEqualTo("PASS");
    }

    // ---------------------------------------------------------------- cash

    @Test
    void cashCountedMustBeTheCashOnTheBills() {
        var bills = List.of(bill("1", "100", null, "100", null, null), bill("2", "50", null, "50", null, null));
        var lorry = new ControlChecks.Paper(false, bills, null, d("150.00"), List.of(), null, null, null);
        assertThat(ControlChecks.cashCount(lorry).status()).isEqualTo("PASS");
        var short_ = new ControlChecks.Paper(false, bills, null, d("140.00"), List.of(), null, null, null);
        assertThat(ControlChecks.cashCount(short_).detail()).contains("150.00").contains("140.00");
        assertThat(ControlChecks.cashCount(paper(false, bills)).status()).isEqualTo("WAITING");
    }

    @Test
    void onTheVanTheRepsCashAndTheDriversCashAreCheckedApart() {
        var bills = List.of(bill("1", "100", "60", "40", null, null));
        var good = new ControlChecks.Paper(true, bills, d("60.00"), d("40.00"), List.of(), null, null, null);
        assertThat(ControlChecks.cashCount(good).status()).isEqualTo("PASS");
        // the total is right (100) but the money is on the wrong person: only separate counting shows it
        var swapped = new ControlChecks.Paper(true, bills, d("50.00"), d("50.00"), List.of(), null, null, null);
        var r = ControlChecks.cashCount(swapped);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("Rep:").contains("Driver:");
    }

    // ---------------------------------------------------------------- stock

    private static ControlChecks.Paper van(PaperStock... lines) {
        return new ControlChecks.Paper(true, List.of(), null, null, List.of(lines), null, null, null);
    }

    @Test
    void theVansCountMustBeTheStockBalanceReportProductByProduct() {
        var system = List.of(new StockRow("CHOCO  MILK 20G", 123), new StockRow("NUTS 40G", 10), new StockRow("EMPTY ONE", 0));
        assertThat(ControlChecks.stock(van(new PaperStock("choco milk 20g", 123), new PaperStock("NUTS 40G", 10)), system).status()).isEqualTo("PASS");

        var r = ControlChecks.stock(van(new PaperStock("CHOCO MILK 20G", 120)), system);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("CHOCO MILK 20G: counted 120, system 123").contains("NUTS 40G: not counted, system 10");
    }

    @Test
    void aProductCountedAsZeroMustBeZeroInTheSystemToo() {
        var r = ControlChecks.stock(van(new PaperStock("NUTS 40G", 0)), List.of(new StockRow("NUTS 40G", 4)));
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("counted 0, system 4");
    }

    @Test
    void anotherLorryMustBeEmptyAfterTheUnload() {
        var lorry = paper(false, List.of());
        assertThat(ControlChecks.stock(lorry, List.of()).status()).isEqualTo("PASS");
        assertThat(ControlChecks.stock(lorry, List.of(new StockRow("A", 0))).status()).isEqualTo("PASS");
        var r = ControlChecks.stock(lorry, List.of(new StockRow("A", 3)));
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("A 3");
        assertThat(ControlChecks.stock(lorry, null).status()).isEqualTo("WAITING");
    }

    @Test
    void theVanWithNoCountYetWaitsEvenIfTheSystemHasStock() {
        assertThat(ControlChecks.stock(van(), List.of(new StockRow("A", 3))).status()).isEqualTo("WAITING");
    }

    @Test
    void theTabletAnswersAreOnlyChecksOnTheVan() {
        var all = new ControlChecks.Paper(true, List.of(), null, null, List.of(), "YES", "YES", "YES");
        assertThat(ControlChecks.tablet(all).status()).isEqualTo("PASS");
        var no = new ControlChecks.Paper(true, List.of(), null, null, List.of(), "NO", "YES", "YES");
        var r = ControlChecks.tablet(no);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.detail()).contains("did not show the stock in the morning");
        assertThat(ControlChecks.tablet(paper(true, List.of())).status()).isEqualTo("WAITING");

        assertThat(ControlChecks.lorry(paper(false, List.of()), null, null)).extracting(Result::code).doesNotContain("TABLET");
        assertThat(ControlChecks.lorry(paper(true, List.of()), null, null)).extracting(Result::code).contains("TABLET");
    }

    // ---------------------------------------------------------------- the safe

    @Test
    void theSafeRollsForwardFromYesterday() {
        var ok = ControlChecks.book(d("12300.00"), d("204848.00"), d("12300.00"), d("0"), d("204848.00"), true);
        assertThat(of(ok, "SAFE").status()).isEqualTo("PASS");
        assertThat(of(ok, "DAY_END").status()).isEqualTo("PASS");

        var short_ = ControlChecks.book(d("12300.00"), d("204848.00"), d("12300.00"), d("0"), d("204348.00"), false);
        assertThat(of(short_, "SAFE").status()).isEqualTo("FAIL");
        assertThat(of(short_, "SAFE").detail()).contains("Should be 204,848.00").contains("counted 204,348.00");
        assertThat(of(short_, "DAY_END").status()).isEqualTo("WAITING");

        assertThat(of(ControlChecks.book(null, BigDecimal.ZERO, null, null, null, false), "SAFE").status()).isEqualTo("WAITING");
    }
}
