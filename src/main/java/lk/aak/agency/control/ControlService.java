package lk.aak.agency.control;

import lk.aak.agency.control.ControlChecks.PaperBill;
import lk.aak.agency.control.ControlChecks.PaperStock;
import lk.aak.agency.control.ControlChecks.Result;
import lk.aak.agency.control.ControlDtos.*;
import lk.aak.agency.control.ControlReports.RdSales;
import lk.aak.agency.control.ControlReports.ReportProblem;
import lk.aak.agency.control.ControlReports.StockRow;
import lk.aak.agency.control.ControlVehicles.Vehicle;
import lk.aak.agency.service.AuditLogService;
import lk.aak.agency.util.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The Control module: saves the paper figures, reads the uploaded CBL reports, runs the checks and keeps the owner's acknowledgements. */
@Service
public class ControlService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    private static final int MAX_BILLS = 80;
    private static final int MAX_STOCK_LINES = 200;
    private static final Set<LocalDate> POYA_DAYS = Set.of(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 23));

    private final ControlLorryDayRepository lorryDays;
    private final ControlDayBookRepository dayBooks;
    private final ControlUploadRepository uploads;
    private final ControlClosureRepository closures;
    private final AuditLogService audit;
    private final Path uploadDirectory;

    public ControlService(ControlLorryDayRepository lorryDays, ControlDayBookRepository dayBooks, ControlUploadRepository uploads,
                          ControlClosureRepository closures, AuditLogService audit) {
        this.lorryDays = lorryDays;
        this.dayBooks = dayBooks;
        this.uploads = uploads;
        this.closures = closures;
        this.audit = audit;
        this.uploadDirectory = Paths.get("uploads", "control").toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the control upload directory.", exception);
        }
    }

    public List<Vehicle> vehicles() {
        return ControlVehicles.ALL;
    }

    /** Sundays and poya days are closed days: no file is expected. */
    public String closedReason(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return "Sunday";
        if (POYA_DAYS.contains(date)) return "Poya day";
        return null;
    }

    // ---------------------------------------------------------------- one lorry, one day

    @Transactional(readOnly = true)
    public LorryView lorry(LocalDate date, String vehicleCode) {
        Vehicle vehicle = vehicleOf(vehicleCode);
        checkDate(date);
        return buildLorry(date, vehicle, lorryDays.findByControlDateAndVehicleCode(date, vehicle.code()).orElse(null),
                uploads.findByControlDateAndVehicleCodeOrderByUploadedAtDescIdDesc(date, vehicle.code()),
                closures.findByControlDateAndVehicleCode(date, vehicle.code()));
    }

    @Transactional
    public LorryView saveLorry(LocalDate date, String vehicleCode, LorryRequest request, String username) {
        Vehicle vehicle = vehicleOf(vehicleCode);
        checkDate(date);
        if (request == null) throw new IllegalArgumentException("Nothing to save.");
        List<BillIn> bills = request.bills() == null ? List.of() : request.bills();
        List<StockIn> stock = request.stock() == null ? List.of() : request.stock();
        if (bills.size() > MAX_BILLS) throw new IllegalArgumentException("A lorry sheet holds at most " + MAX_BILLS + " bills.");
        if (stock.size() > MAX_STOCK_LINES) throw new IllegalArgumentException("A stock count holds at most " + MAX_STOCK_LINES + " products.");

        ControlLorryDay day = lorryDays.findByControlDateAndVehicleCode(date, vehicle.code()).orElseGet(() -> new ControlLorryDay(date, vehicle.code()));
        day.setRepName(clean(request.repName(), 100));
        day.setDriverName(clean(request.driverName(), 100));
        day.setCashCountedRep(amount(request.cashCountedRep(), "Cash counted (rep)"));
        day.setCashCountedDriver(amount(request.cashCountedDriver(), "Cash counted (driver)"));
        day.setTabletStockMorning(yesNo(request.tabletStockMorning()));
        day.setTabletStockAfter(yesNo(request.tabletStockAfter()));
        day.setMorningStockOk(yesNo(request.morningStockOk()));
        day.touch(username);

        day.getBills().clear();
        Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (BillIn b : bills) {
            String no = clean(b.billNo(), 40);
            boolean empty = no == null && b.billValue() == null && b.cashRep() == null && b.cashDriver() == null && b.chequeAmount() == null && b.creditAmount() == null;
            if (empty) continue;
            if (no == null) throw new IllegalArgumentException("Line " + (n + 1) + " has amounts but no bill number.");
            if (!seen.add(no)) throw new IllegalArgumentException("Bill " + no + " is written twice.");
            day.getBills().add(new ControlBillLine(day, ++n, no, amount(b.billValue(), "Bill value of " + no), amount(b.cashRep(), "Cash (rep) of " + no),
                    amount(b.cashDriver(), "Cash (driver) of " + no), amount(b.chequeAmount(), "Cheque of " + no), clean(b.chequeNo(), 40), amount(b.creditAmount(), "Credit of " + no)));
        }
        day.getStock().clear();
        int s = 0;
        for (StockIn l : stock) {
            String name = clean(l.productName(), 200);
            if (name == null && l.counted() == null && l.loadTomorrow() == null) continue;
            if (name == null) throw new IllegalArgumentException("A stock line has a number but no product.");
            if ((l.counted() != null && l.counted() < 0) || (l.loadTomorrow() != null && l.loadTomorrow() < 0)) throw new IllegalArgumentException("Stock numbers cannot be negative (" + name + ").");
            day.getStock().add(new ControlStockLine(day, ++s, name, l.counted(), l.loadTomorrow()));
        }
        lorryDays.save(day);
        audit.record("CONTROL_SAVE", "ControlLorryDay", day.getId(), vehicle.code() + " " + date + ": " + n + " bills, " + s + " stock lines");
        return lorry(date, vehicle.code());
    }

    // ---------------------------------------------------------------- uploads

    @Transactional
    public UploadView upload(LocalDate date, String vehicleCode, String kind, MultipartFile file, String username) {
        Vehicle vehicle = vehicleOf(vehicleCode);
        checkDate(date);
        if (!ControlUpload.RD_SALES.equals(kind) && !ControlUpload.STOCK_BALANCE.equals(kind)) throw new IllegalArgumentException("Choose which CBL report this is.");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please choose a file to upload.");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("The file could not be read.");
        }
        String original = cleanName(file.getOriginalFilename());
        String status = ControlUpload.READ, message = null;
        Integer rows = null;
        try {
            if (ControlUpload.RD_SALES.equals(kind)) {
                RdSales rd = ControlReports.readRdSales(data, original);
                if (!rd.date().equals(date)) throw new ReportProblem("This report is for " + rd.date() + " but you are filling " + date + ". Use the file of the right bill date.");
                if (!rd.vehicle().equalsIgnoreCase(vehicle.code())) throw new ReportProblem("This report is for " + rd.vehicle() + " but you are filling " + vehicle.code() + ".");
                rows = rd.bills().size();
                message = rd.bills().size() + " bills, net " + ControlChecks.money(rd.netTotal());
            } else {
                List<StockRow> stock = ControlReports.readStockBalance(data, original);
                rows = stock.size();
                message = stock.size() + " products";
            }
        } catch (ReportProblem problem) {
            status = ControlUpload.PROBLEM;
            message = problem.getMessage();
        }
        String stored = UUID.randomUUID() + extensionOf(original);
        try {
            Files.write(uploadDirectory.resolve(stored), data);
        } catch (IOException exception) {
            throw new IllegalStateException("The uploaded file could not be stored.", exception);
        }
        ControlUpload saved = uploads.save(new ControlUpload(date, vehicle.code(), kind, original, stored, sha256(data), status, message, rows, username));
        audit.record("CONTROL_UPLOAD", "ControlUpload", saved.getId(), kind + " " + vehicle.code() + " " + date + " " + status + (message == null ? "" : ": " + message));
        return uploadView(saved, status.equals(ControlUpload.READ));
    }

    @Transactional
    public void deleteUpload(LocalDate date, String vehicleCode, Long id, String username) {
        Vehicle vehicle = vehicleOf(vehicleCode);
        ControlUpload upload = uploads.findById(id).orElseThrow(() -> new IllegalArgumentException("Upload not found."));
        if (!upload.getControlDate().equals(date) || !upload.getVehicleCode().equals(vehicle.code())) throw new IllegalArgumentException("Upload not found.");
        try {
            Files.deleteIfExists(uploadDirectory.resolve(upload.getStoredName()));
        } catch (IOException ignored) {
            // The row is what matters; a leftover file does no harm.
        }
        uploads.delete(upload);
        audit.record("CONTROL_UPLOAD_DELETE", "ControlUpload", id, upload.getKind() + " " + vehicle.code() + " " + date + " removed by " + username);
    }

    // ---------------------------------------------------------------- the safe and the day's end

    @Transactional(readOnly = true)
    public BookView book(LocalDate date) {
        checkDate(date);
        return buildBook(date, dayBooks.findByControlDate(date).orElse(null), lorryDays.findByControlDate(date));
    }

    @Transactional
    public BookView saveBook(LocalDate date, BookRequest request, String username) {
        checkDate(date);
        if (request == null) throw new IllegalArgumentException("Nothing to save.");
        ControlDayBook book = dayBooks.findByControlDate(date).orElseGet(() -> new ControlDayBook(date));
        book.setSafeOpening(amount(request.safeOpening(), "Safe at the start of the day"));
        book.setBanked(amount(request.banked(), "Banked"));
        book.setPaidOut(amount(request.paidOut(), "Paid out"));
        book.setSafeCounted(amount(request.safeCounted(), "Counted in the safe"));
        book.setDayEndDone(request.dayEndDone());
        book.touch(username);
        dayBooks.save(book);
        audit.record("CONTROL_BOOK_SAVE", "ControlDayBook", null, date + ": safe " + request.safeCounted() + ", day end " + request.dayEndDone());
        return book(date);
    }

    /** The owner's signature the next morning. */
    @Transactional
    public BookView sign(LocalDate date, String username) {
        checkDate(date);
        ControlDayBook book = dayBooks.findByControlDate(date).orElseGet(() -> new ControlDayBook(date));
        book.sign(username);
        book.touch(username);
        dayBooks.save(book);
        audit.record("CONTROL_SIGN", "ControlDayBook", null, date + " signed by " + username);
        return book(date);
    }

    // ---------------------------------------------------------------- acknowledging a check

    /** Only a check that is failing can be acknowledged, and it stays visible as failing. A note is required. */
    @Transactional
    public LorryView close(LocalDate date, String vehicleCode, String checkCode, String note, String username) {
        Vehicle vehicle = vehicleOf(vehicleCode);
        LorryView view = lorry(date, vehicle.code());
        CheckView check = view.checks().stream().filter(c -> c.code().equals(checkCode)).findFirst().orElseThrow(() -> new IllegalArgumentException("That check does not exist."));
        if (!ControlChecks.FAIL.equals(check.status())) throw new IllegalArgumentException("Only a check that does not agree can be acknowledged.");
        String text = clean(note, 500);
        if (text == null) throw new IllegalArgumentException("Write a note: what is the reason, and what was decided?");
        closures.findByControlDateAndVehicleCodeAndCheckCode(date, vehicle.code(), checkCode).ifPresent(closures::delete);
        closures.flush();
        closures.save(new ControlClosure(date, vehicle.code(), checkCode, text, username));
        audit.record("CONTROL_ACK", "ControlClosure", null, vehicle.code() + " " + date + " " + checkCode + ": " + text);
        return lorry(date, vehicle.code());
    }

    // ---------------------------------------------------------------- the day, and a range of days

    @Transactional(readOnly = true)
    public DaySummary day(LocalDate date) {
        checkDate(date);
        return summaries(date, date).get(0);
    }

    /** One entry per date, newest first. The range is at most 62 days. */
    @Transactional(readOnly = true)
    public List<DaySummary> board(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Choose the first and the last date.");
        if (to.isAfter(BusinessTime.today())) to = BusinessTime.today();
        if (to.isBefore(from)) throw new IllegalArgumentException("Choose dates up to today.");
        if (from.plusDays(62).isBefore(to)) throw new IllegalArgumentException("Choose at most 62 days at a time.");
        return summaries(from, to);
    }

    private List<DaySummary> summaries(LocalDate from, LocalDate to) {
        Map<LocalDate, List<ControlLorryDay>> days = new LinkedHashMap<>();
        for (ControlLorryDay d : lorryDays.findByControlDateBetween(from, to)) days.computeIfAbsent(d.getControlDate(), k -> new ArrayList<>()).add(d);
        Map<LocalDate, ControlDayBook> books = new LinkedHashMap<>();
        for (ControlDayBook b : dayBooks.findByControlDateBetween(from, to)) books.put(b.getControlDate(), b);
        Map<String, List<ControlUpload>> ups = new LinkedHashMap<>();
        for (ControlUpload u : uploads.findByControlDateBetween(from, to)) ups.computeIfAbsent(u.getControlDate() + "|" + u.getVehicleCode(), k -> new ArrayList<>()).add(u);
        for (List<ControlUpload> list : ups.values()) list.sort((a, b) -> b.getId().compareTo(a.getId()));
        Map<String, List<ControlClosure>> cls = new LinkedHashMap<>();
        for (ControlClosure c : closures.findByControlDateBetween(from, to)) cls.computeIfAbsent(c.getControlDate() + "|" + c.getVehicleCode(), k -> new ArrayList<>()).add(c);

        List<DaySummary> out = new ArrayList<>();
        for (LocalDate date = to; !date.isBefore(from); date = date.minusDays(1)) {
            List<ControlLorryDay> paperDays = days.getOrDefault(date, List.of());
            List<LorrySummary> lorries = new ArrayList<>();
            for (Vehicle v : ControlVehicles.ALL) {
                ControlLorryDay paper = paperDays.stream().filter(p -> p.getVehicleCode().equals(v.code())).findFirst().orElse(null);
                List<ControlUpload> u = ups.getOrDefault(date + "|" + v.code(), List.of());
                if (paper == null && u.isEmpty()) continue;
                LorryView view = buildLorry(date, v, paper, u, cls.getOrDefault(date + "|" + v.code(), List.of()));
                lorries.add(new LorrySummary(v.code(), v.vanSales(), view.hasPaper(),
                        u.stream().anyMatch(x -> x.getKind().equals(ControlUpload.RD_SALES) && x.getStatus().equals(ControlUpload.READ)),
                        u.stream().anyMatch(x -> x.getKind().equals(ControlUpload.STOCK_BALANCE) && x.getStatus().equals(ControlUpload.READ)),
                        view.counts(), light(view.counts()), view.checks().stream().filter(c -> c.status().equals(ControlChecks.FAIL)).toList()));
            }
            ControlDayBook book = books.get(date);
            BookView bookView = buildBook(date, book, paperDays);
            String closed = closedReason(date);
            boolean missing = closed == null && lorries.isEmpty() && book == null;
            String light = missing ? "RED" : lorries.isEmpty() && book == null ? "GREY" : worst(lorries.stream().map(LorrySummary::light).toList(), light(bookView.counts()));
            out.add(new DaySummary(date, closed, lorries, bookView, light, missing));
        }
        return out;
    }

    // ---------------------------------------------------------------- building views

    private LorryView buildLorry(LocalDate date, Vehicle vehicle, ControlLorryDay day, List<ControlUpload> ups, List<ControlClosure> cls) {
        ControlUpload rdUsed = latestRead(ups, ControlUpload.RD_SALES), stockUsed = latestRead(ups, ControlUpload.STOCK_BALANCE);
        RdSales rd = rdUsed == null ? null : readRd(rdUsed);
        List<StockRow> stock = stockUsed == null ? null : readStock(stockUsed);
        List<PaperBill> paperBills = new ArrayList<>();
        List<PaperStock> paperStock = new ArrayList<>();
        List<BillIn> billsOut = new ArrayList<>();
        List<StockIn> stockOut = new ArrayList<>();
        if (day != null) {
            for (ControlBillLine b : day.getBills()) {
                paperBills.add(new PaperBill(b.getBillNo(), b.getBillValue(), b.getCashRep(), b.getCashDriver(), b.getChequeAmount(), b.getCreditAmount()));
                billsOut.add(new BillIn(b.getBillNo(), b.getBillValue(), b.getCashRep(), b.getCashDriver(), b.getChequeAmount(), b.getChequeNo(), b.getCreditAmount()));
            }
            for (ControlStockLine l : day.getStock()) {
                paperStock.add(new PaperStock(l.getProductName(), l.getCounted()));
                stockOut.add(new StockIn(l.getProductName(), l.getCounted(), l.getLoadTomorrow()));
            }
        }
        ControlChecks.Paper paper = new ControlChecks.Paper(vehicle.vanSales(), paperBills, day == null ? null : day.getCashCountedRep(), day == null ? null : day.getCashCountedDriver(),
                paperStock, day == null ? null : day.getTabletStockMorning(), day == null ? null : day.getTabletStockAfter(), day == null ? null : day.getMorningStockOk());
        List<CheckView> checks = new ArrayList<>();
        for (Result r : ControlChecks.lorry(paper, rd, stock)) checks.add(view(r, cls));
        List<UploadView> uploadViews = new ArrayList<>();
        for (ControlUpload u : ups) uploadViews.add(uploadView(u, u == rdUsed || u == stockUsed));
        return new LorryView(date, vehicle.code(), vehicle.vanSales(), day != null, day == null ? null : day.getRepName(), day == null ? null : day.getDriverName(),
                day == null ? null : day.getCashCountedRep(), day == null ? null : day.getCashCountedDriver(), day == null ? null : day.getTabletStockMorning(),
                day == null ? null : day.getTabletStockAfter(), day == null ? null : day.getMorningStockOk(), billsOut, stockOut, uploadViews, checks, counts(checks),
                stock == null ? List.of() : stock.stream().map(r -> new SystemStockRow(r.sku(), r.quantity())).toList(),
                day == null ? null : day.getUpdatedBy(), day == null ? null : day.getUpdatedAt());
    }

    /** A stored report that can no longer be read (file removed) counts as not uploaded. */
    private RdSales readRd(ControlUpload u) {
        try {
            return ControlReports.readRdSales(Files.readAllBytes(uploadDirectory.resolve(u.getStoredName())), u.getOriginalName());
        } catch (IOException | ReportProblem exception) {
            return null;
        }
    }

    private List<StockRow> readStock(ControlUpload u) {
        try {
            return ControlReports.readStockBalance(Files.readAllBytes(uploadDirectory.resolve(u.getStoredName())), u.getOriginalName());
        } catch (IOException | ReportProblem exception) {
            return null;
        }
    }

    private BookView buildBook(LocalDate date, ControlDayBook book, List<ControlLorryDay> lorries) {
        BigDecimal counted = BigDecimal.ZERO;
        for (ControlLorryDay d : lorries) counted = counted.add(ControlChecks.z(d.getCashCountedRep())).add(ControlChecks.z(d.getCashCountedDriver()));
        List<CheckView> checks = new ArrayList<>();
        for (Result r : ControlChecks.book(book == null ? null : book.getSafeOpening(), counted, book == null ? null : book.getBanked(),
                book == null ? null : book.getPaidOut(), book == null ? null : book.getSafeCounted(), book != null && book.isDayEndDone())) checks.add(view(r, List.of()));
        return new BookView(date, book == null ? null : book.getSafeOpening(), book == null ? null : book.getBanked(), book == null ? null : book.getPaidOut(),
                book == null ? null : book.getSafeCounted(), book != null && book.isDayEndDone(), book == null ? null : book.getSignedBy(), book == null ? null : book.getSignedAt(),
                counted, checks, counts(checks));
    }

    private static CheckView view(Result r, List<ControlClosure> cls) {
        ClosureView ack = null;
        if (ControlChecks.FAIL.equals(r.status())) {
            for (ControlClosure c : cls) if (c.getCheckCode().equals(r.code())) ack = new ClosureView(c.getNote(), c.getClosedBy(), c.getClosedAt());
        }
        return new CheckView(r.code(), r.title(), r.status(), r.paper(), r.comparedWith(), r.compared(), r.detail(), ack);
    }

    private static Counts counts(List<CheckView> checks) {
        int failing = 0, ack = 0, waiting = 0, passing = 0;
        for (CheckView c : checks) {
            switch (c.status()) {
                case ControlChecks.FAIL -> { if (c.acknowledged() != null) ack++; else failing++; }
                case ControlChecks.WAITING -> waiting++;
                case ControlChecks.PASS -> passing++;
                default -> { }
            }
        }
        return new Counts(failing, ack, waiting, passing);
    }

    /** RED if anything fails and nobody acknowledged it, AMBER if something is still waiting, otherwise GREEN. */
    static String light(Counts c) { return c.failing() > 0 ? "RED" : c.waiting() > 0 ? "AMBER" : "GREEN"; }

    private static String worst(List<String> lights, String bookLight) {
        if (lights.contains("RED") || "RED".equals(bookLight)) return "RED";
        if (lights.contains("AMBER") || "AMBER".equals(bookLight)) return "AMBER";
        return "GREEN";
    }

    private static ControlUpload latestRead(List<ControlUpload> ups, String kind) {
        for (ControlUpload u : ups) if (u.getKind().equals(kind) && u.getStatus().equals(ControlUpload.READ)) return u;
        return null;
    }

    private static UploadView uploadView(ControlUpload u, boolean inUse) {
        return new UploadView(u.getId(), u.getKind(), u.getOriginalName(), u.getStatus(), u.getMessage(), u.getRowsRead(), u.getUploadedBy(), u.getUploadedAt(), inUse);
    }

    // ---------------------------------------------------------------- small helpers

    private static Vehicle vehicleOf(String code) {
        return ControlVehicles.find(code);
    }

    private static void checkDate(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Choose a date.");
        if (date.isAfter(BusinessTime.today())) throw new IllegalArgumentException("That date has not come yet.");
        if (date.isBefore(BusinessTime.today().minusYears(5))) throw new IllegalArgumentException("That date is too long ago.");
    }

    private static String clean(String text, int max) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new IllegalArgumentException("\"" + t.substring(0, 20) + "...\" is too long.");
        return t;
    }

    private static BigDecimal amount(BigDecimal value, String what) {
        if (value == null) return null;
        if (value.signum() < 0) throw new IllegalArgumentException(what + " cannot be negative.");
        if (value.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(what + " is too large - please check the number.");
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String yesNo(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!v.equals("YES") && !v.equals("NO")) throw new IllegalArgumentException("Answer Yes or No.");
        return v;
    }

    private static String cleanName(String name) {
        String n = name == null ? "report" : name.replaceAll("[\\\\/]", "_").trim();
        return n.isEmpty() ? "report" : n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.(xlsx|xls|csv|pdf)") ? ext : "";
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
