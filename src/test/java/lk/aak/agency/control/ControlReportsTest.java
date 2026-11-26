package lk.aak.agency.control;

import lk.aak.agency.control.ControlReports.RdSales;
import lk.aak.agency.control.ControlReports.ReportProblem;
import lk.aak.agency.control.ControlReports.StockRow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static lk.aak.agency.control.ControlFixtures.Bill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The readers for the Connect Plus downloads: what they understand, and that they refuse (in plain words) what they do not. */
class ControlReportsTest {

    @Test
    void readsTheRdSalesExcel() throws Exception {
        byte[] file = ControlFixtures.rdSales("20-11-2026", "MAKOLARTM-A", List.of(
                new Bill("4939", "SOME STORES", "45214.20", "Credit", "0.00"),
                new Bill("4934", "OTHER STORES", "41472.00", "Cash", "41472.00")));

        RdSales rd = ControlReports.readRdSales(file, "rd.xlsx");

        assertThat(rd.date()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(rd.vehicle()).isEqualTo("MAKOLARTM-A");
        assertThat(rd.bills()).extracting(ControlReports.RdBill::serial).containsExactly("4939", "4934");
        assertThat(rd.bills().get(1).cashPaid()).isEqualByComparingTo("41472.00");
        assertThat(rd.bills().get(0).paymentMode()).isEqualTo("Credit");
        assertThat(rd.netTotal()).isEqualByComparingTo("86686.20");
    }

    @Test
    void aDayWithNoBillsIsAnEmptyReportNotAnError() throws Exception {
        RdSales rd = ControlReports.readRdSales(ControlFixtures.rdSales("20-11-2026", "MAKOLARTM-AV2", List.of()), "rd.xlsx");
        assertThat(rd.bills()).isEmpty();
        assertThat(rd.netTotal()).isEqualByComparingTo("0");
    }

    @Test
    void readsTheStockBalanceFromCsvIncludingNamesWithCommas() {
        List<StockRow> rows = ControlReports.readStockBalance(ControlFixtures.stockCsv("CHOCO-LA MILK 20G MRP 50.00|123", "NUTS, SALTED 40G|7"), "export.csv");
        assertThat(rows).containsExactly(new StockRow("CHOCO-LA MILK 20G MRP 50.00", 123), new StockRow("NUTS, SALTED 40G", 7));
    }

    @Test
    void readsTheStockBalanceFromExcelAndStopsAtTheTotalsRow() throws Exception {
        List<StockRow> rows = ControlReports.readStockBalance(ControlFixtures.stockExcel("A 10G|5", "B 20G|0", "C 30G|12"), "report.xlsx");
        assertThat(rows).containsExactly(new StockRow("A 10G", 5), new StockRow("B 20G", 0), new StockRow("C 30G", 12));
    }

    @Test
    void aPdfIsRefusedAndTheMessageSaysWhichButtonToUse() {
        byte[] pdf = "%PDF-1.4 not really".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> ControlReports.readRdSales(pdf, "rd.pdf")).isInstanceOf(ReportProblem.class).hasMessageContaining("Excel button");
        assertThatThrownBy(() -> ControlReports.readStockBalance(pdf, "x.pdf")).isInstanceOf(ReportProblem.class).hasMessageContaining("CSV");
    }

    @Test
    void aReportWithTheWrongColumnsIsRefusedInsteadOfReadWrongly() throws Exception {
        assertThatThrownBy(() -> ControlReports.readStockBalance("Name,Qty\nA,1\n".getBytes(StandardCharsets.UTF_8), "x.csv")).isInstanceOf(ReportProblem.class).hasMessageContaining("SKU");
        assertThatThrownBy(() -> ControlReports.readRdSales("hello,world\n".getBytes(StandardCharsets.UTF_8), "x.csv")).isInstanceOf(ReportProblem.class).hasMessageContaining("Serial No");
        assertThatThrownBy(() -> ControlReports.readRdSales(new byte[0], "x.xlsx")).isInstanceOf(ReportProblem.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> ControlReports.readStockBalance("SKU,Quantity\nA,twelve\n".getBytes(StandardCharsets.UTF_8), "x.csv")).isInstanceOf(ReportProblem.class).hasMessageContaining("not a number");
        assertThatThrownBy(() -> ControlReports.readStockBalance("SKU,Quantity\nA,1.5\n".getBytes(StandardCharsets.UTF_8), "x.csv")).isInstanceOf(ReportProblem.class).hasMessageContaining("whole number");
    }

    /** Runs only on a machine that has the real downloads in AAK-Agency/cbl-samples (they are not in the repository). */
    @Test
    void theRealConnectPlusDownloadsAreUnderstood() throws Exception {
        Path samples = Path.of("../../cbl-samples").toAbsolutePath().normalize();
        Path rd = samples.resolve("rd-sales-entry_MAKOLARTM-A_20-11-2026.xlsx");
        Path csv = samples.resolve("stock-balance_MAKOLARTM-AV4_22-11-2026.csv");
        Path xlsx = samples.resolve("stock-balance_MAKOLARTM-AV4_22-11-2026.xlsx");
        Assumptions.assumeTrue(Files.exists(rd) && Files.exists(csv) && Files.exists(xlsx), "real samples not on this machine");

        RdSales sales = ControlReports.readRdSales(Files.readAllBytes(rd), rd.getFileName().toString());
        assertThat(sales.bills()).hasSize(7);
        assertThat(sales.netTotal()).isEqualByComparingTo("334559.02");
        assertThat(sales.vehicle()).isEqualTo("MAKOLARTM-A");

        List<StockRow> fromCsv = ControlReports.readStockBalance(Files.readAllBytes(csv), csv.getFileName().toString());
        List<StockRow> fromExcel = ControlReports.readStockBalance(Files.readAllBytes(xlsx), xlsx.getFileName().toString());
        assertThat(fromCsv).hasSize(63);
        assertThat(fromExcel).isEqualTo(fromCsv);
        assertThat(fromCsv.stream().mapToInt(StockRow::quantity).sum()).isEqualTo(4423); // the total on the Excel report
    }
}
