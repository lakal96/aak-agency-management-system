package lk.aak.agency.control;

import lk.aak.agency.control.ControlDtos.BookView;
import lk.aak.agency.control.ControlDtos.CheckView;
import lk.aak.agency.control.ControlDtos.DaySummary;
import lk.aak.agency.control.ControlDtos.LorryView;
import lk.aak.agency.control.ControlTaskDefs.Def;
import lk.aak.agency.control.ControlTaskDefs.Freq;
import lk.aak.agency.control.ControlTaskDefs.Input;
import lk.aak.agency.control.ControlTaskDefs.Scope;
import lk.aak.agency.control.ControlTaskDefs.Who;
import lk.aak.agency.control.ControlTaskDtos.*;
import lk.aak.agency.control.ControlVehicles.Vehicle;
import lk.aak.agency.service.AuditLogService;
import lk.aak.agency.util.BusinessTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The to-do list of the agency: which tasks fall on a day (daily, weekly on Saturday, monthly on the last working day), which are done,
 * which are overdue, and what still stops a task from being confirmed. The work restarts from a start date; days before it have no tasks.
 */
@Service
public class ControlTaskService {

    private static final int MAX_BACKLOG_DAYS = 400;

    private final ControlService control;
    private final ControlTaskDoneRepository done;
    private final ControlEntryRepository entries;
    private final ControlLorrySettingRepository settings;
    private final ControlLorryDayRepository lorryDays;
    private final ControlDayBookRepository dayBooks;
    private final AuditLogService audit;
    private final LocalDate startDate;

    public ControlTaskService(ControlService control, ControlTaskDoneRepository done, ControlEntryRepository entries, ControlLorrySettingRepository settings,
                              ControlLorryDayRepository lorryDays, ControlDayBookRepository dayBooks, AuditLogService audit,
                              @Value("${app.control.start-date:2026-11-19}") String startDate) {
        this.control = control;
        this.done = done;
        this.entries = entries;
        this.settings = settings;
        this.lorryDays = lorryDays;
        this.dayBooks = dayBooks;
        this.audit = audit;
        this.startDate = LocalDate.parse(startDate);
    }

    public LocalDate startDate() { return startDate; }

    // ---------------------------------------------------------------- what falls on a day

    /** One task that applies to a date (and lorry), before looking at whether it is done. */
    record Instance(Def def, String vehicle, boolean van) { }

    /** Everything about the days in a range that the instances depend on, loaded in a few queries. */
    private final class Range {
        final Map<LocalDate, List<ControlTaskDone>> rows = new HashMap<>();
        final Map<LocalDate, ControlDayBook> books = new HashMap<>();
        final Set<LocalDate> active = new java.util.HashSet<>();
        final Set<String> inactiveLorries;

        Range(LocalDate from, LocalDate to) {
            for (ControlTaskDone r : done.findByControlDateBetween(from, to)) {
                rows.computeIfAbsent(r.getControlDate(), k -> new ArrayList<>()).add(r);
                active.add(r.getControlDate());
            }
            for (ControlDayBook b : dayBooks.findByControlDateBetween(from, to)) books.put(b.getControlDate(), b);
            for (ControlLorryDay d : lorryDays.findByControlDateBetween(from, to)) active.add(d.getControlDate());
            for (ControlEntry e : entries.findByControlDateBetween(from, to)) active.add(e.getControlDate());
            inactiveLorries = settings.findAll().stream().filter(s -> !s.isActive()).map(ControlLorrySetting::getVehicleCode).collect(Collectors.toSet());
        }

        List<ControlTaskDone> of(LocalDate d) { return rows.getOrDefault(d, List.of()); }

        boolean noRun(LocalDate d, String vehicle) {
            return of(d).stream().anyMatch(r -> r.getStatus().equals(ControlTaskDone.NO_RUN) && r.getVehicleCode().equals(vehicle));
        }

        /** A Sunday or a poya day counts as a working day only if someone worked on it. */
        boolean worked(LocalDate d) {
            return control.closedReason(d) == null || active.contains(d) || of(d).stream().anyMatch(r -> r.getStatus().equals(ControlTaskDone.OPEN_DAY));
        }
    }

    private List<Instance> instances(LocalDate date, Range range) {
        List<Instance> out = new ArrayList<>();
        if (date.isBefore(startDate) || date.isAfter(BusinessTime.today()) || !range.worked(date)) return out;
        for (Def def : ControlTaskDefs.ALL) {
            if (def.freq() == Freq.WEEKLY && date.getDayOfWeek() != DayOfWeek.SATURDAY) continue;
            if (def.freq() == Freq.MONTHLY && !date.equals(lastWorkingDay(date))) continue;
            if (def.scope() == Scope.DAY) {
                out.add(new Instance(def, "", false));
                continue;
            }
            for (Vehicle v : ControlVehicles.ALL) {
                if (range.inactiveLorries.contains(v.code()) || range.noRun(date, v.code())) continue;
                if ((def.who() == Who.VAN && !v.vanSales()) || (def.who() == Who.OTHERS && v.vanSales())) continue;
                out.add(new Instance(def, v.code(), v.vanSales()));
            }
        }
        return out;
    }

    private LocalDate lastWorkingDay(LocalDate date) {
        LocalDate d = YearMonth.from(date).atEndOfMonth();
        while (control.closedReason(d) != null) d = d.minusDays(1);
        return d;
    }

    /** Tasks the staff must do. Optional tasks and the owner's signature are shown but never counted as work left undone. */
    private static boolean counted(Def def) { return !def.optional() && def.input() != Input.SIGN; }

    private static boolean isDone(Instance i, LocalDate date, Range range) {
        if (i.def().input() == Input.SIGN) {
            ControlDayBook book = range.books.get(date);
            return book != null && book.getSignedBy() != null;
        }
        return range.of(date).stream().anyMatch(r -> r.getStatus().equals(ControlTaskDone.DONE) && r.getTaskCode().equals(i.def().code()) && r.getVehicleCode().equals(i.vehicle()));
    }

    // ---------------------------------------------------------------- the calendar and the backlog

    @Transactional(readOnly = true)
    public List<CalendarDay> calendar(YearMonth month) {
        LocalDate first = month.atDay(1), last = month.atEndOfMonth();
        Range range = new Range(first, last);
        List<CalendarDay> out = new ArrayList<>();
        LocalDate today = BusinessTime.today();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            String closed = control.closedReason(d);
            ControlDayBook book = range.books.get(d);
            boolean signed = book != null && book.getSignedBy() != null;
            if (d.isAfter(today)) { out.add(new CalendarDay(d, "FUTURE", 0, 0, closed, false)); continue; }
            if (d.isBefore(startDate)) { out.add(new CalendarDay(d, "BEFORE_START", 0, 0, closed, signed)); continue; }
            if (!range.worked(d)) { out.add(new CalendarDay(d, "CLOSED", 0, 0, closed, signed)); continue; }
            int required = 0, finished = 0;
            for (Instance i : instances(d, range)) {
                if (!counted(i.def())) continue;
                required++;
                if (isDone(i, d, range)) finished++;
            }
            String state = required == 0 ? "NONE" : finished == required ? "ALL_DONE" : d.isBefore(today) ? "OVERDUE" : "OPEN";
            out.add(new CalendarDay(d, state, required, finished, closed, signed));
        }
        return out;
    }

    /** The catch-up list: every day from the start date up to yesterday that still has tasks to do, oldest first. */
    @Transactional(readOnly = true)
    public Backlog backlog() {
        LocalDate today = BusinessTime.today();
        LocalDate last = today.minusDays(1);
        if (last.isBefore(startDate)) return new Backlog(startDate, 0, 0, List.of());
        LocalDate from = startDate.isBefore(last.minusDays(MAX_BACKLOG_DAYS)) ? last.minusDays(MAX_BACKLOG_DAYS) : startDate;
        Range range = new Range(from, last);
        List<BacklogDay> list = new ArrayList<>();
        int tasks = 0;
        for (LocalDate d = from; !d.isAfter(last); d = d.plusDays(1)) {
            int required = 0, open = 0;
            for (Instance i : instances(d, range)) {
                if (!counted(i.def())) continue;
                required++;
                if (!isDone(i, d, range)) open++;
            }
            if (open > 0) { list.add(new BacklogDay(d, open, required)); tasks += open; }
        }
        return new Backlog(startDate, list.size(), tasks, list);
    }

    @Transactional(readOnly = true)
    public Progress progress() {
        Backlog b = backlog();
        LocalDate today = BusinessTime.today();
        int required = 0, finished = 0;
        if (!today.isBefore(startDate)) {
            Range range = new Range(today, today);
            for (Instance i : instances(today, range)) {
                if (!counted(i.def())) continue;
                required++;
                if (isDone(i, today, range)) finished++;
            }
        }
        return new Progress(b.tasks(), b.days(), b.list().isEmpty() ? null : b.list().get(0).date(), required, finished);
    }

    // ---------------------------------------------------------------- one day in full

    @Transactional(readOnly = true)
    public DayTasks day(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Choose a date.");
        LocalDate today = BusinessTime.today();
        Range range = new Range(date, date);
        String closed = control.closedReason(date);
        ControlDayBook book = range.books.get(date);
        boolean signed = book != null && book.getSignedBy() != null;
        List<LorryState> lorries = new ArrayList<>();
        List<ControlLorryDay> paper = lorryDays.findByControlDate(date);
        for (Vehicle v : ControlVehicles.ALL) {
            boolean hasBills = paper.stream().anyMatch(p -> p.getVehicleCode().equals(v.code()) && !p.getBills().isEmpty());
            lorries.add(new LorryState(v.code(), v.vanSales(), !range.inactiveLorries.contains(v.code()), range.noRun(date, v.code()), hasBills));
        }
        boolean future = date.isAfter(today), before = date.isBefore(startDate), worked = range.worked(date);
        List<TaskView> views = new ArrayList<>();
        int required = 0, finished = 0;
        Map<String, LorryView> lorryCache = new HashMap<>();
        BookView bookView = null;
        List<Instance> inst = instances(date, range);
        for (Instance i : inst) {
            ControlTaskDone row = range.of(date).stream().filter(r -> r.getStatus().equals(ControlTaskDone.DONE) && r.getTaskCode().equals(i.def().code()) && r.getVehicleCode().equals(i.vehicle())).findFirst().orElse(null);
            boolean isDone = isDone(i, date, range);
            boolean count = counted(i.def());
            if (count) { required++; if (isDone) finished++; }
            List<String> blockers = isDone ? List.of() : blockersOf(i, date, inst, range, lorryCache);
            String by = row != null ? row.getDoneBy() : isDone && book != null ? book.getSignedBy() : null;
            views.add(view(i, date, isDone, !isDone && count && date.isBefore(today), by, row == null ? (isDone && book != null ? book.getSignedAt() : null) : row.getDoneAt(), row == null ? null : row.getNote(), blockers));
        }
        boolean overdue = required > finished && date.isBefore(today) && worked && !future && !before;
        return new DayTasks(date, closed, worked, future, before, required, finished, overdue, signed, book == null ? null : book.getSignedBy(), lorries, views);
    }

    private TaskView view(Instance i, LocalDate date, boolean isDone, boolean overdue, String by, java.time.LocalDateTime at, String note, List<String> blockers) {
        Def d = i.def();
        ControlTaskDefs.EntryKind kind = d.entryKind() == null ? null : ControlTaskDefs.ENTRY_KINDS.get(d.entryKind());
        return new TaskView(date + "|" + d.code() + "|" + i.vehicle(), d.code(), d.title(), d.slot(), d.scope().name(), i.vehicle(), i.van(), d.input().name(), d.entryKind(),
                kind == null ? null : kind.title(), kind == null ? List.of() : kind.columns(), d.optional(), d.person(), d.screen(), d.steps(), d.proof(),
                isDone ? "DONE" : "OPEN", overdue, by, at, note, blockers);
    }

    /** In plain words, what still stops this task from being confirmed. Empty means it can be confirmed now. */
    private List<String> blockersOf(Instance i, LocalDate date, List<Instance> dayInstances, Range range, Map<String, LorryView> cache) {
        Def d = i.def();
        List<String> out = new ArrayList<>();
        LorryView lv = null;
        if (d.scope() == Scope.LORRY && (!d.checks().isEmpty() || d.input() == Input.TABLET_MORNING || d.input() == Input.STOCK_COUNT)) {
            lv = cache.computeIfAbsent(i.vehicle(), v -> control.lorry(date, v));
        }
        if (lv != null) {
            for (String code : d.checks()) {
                CheckView c = lv.checks().stream().filter(x -> x.code().equals(code)).findFirst().orElse(null);
                if (c == null) continue;
                if (ControlChecks.FAIL.equals(c.status()) && c.acknowledged() == null) out.add(c.title() + ": " + c.detail() + " (the owner can acknowledge it on the dashboard)");
                else if (ControlChecks.WAITING.equals(c.status())) out.add(c.detail());
            }
            if (d.input() == Input.TABLET_MORNING) {
                if (lv.tabletStockMorning() == null) out.add("Answer: did the tablet show the available stock?");
                if (lv.morningStockOk() == null) out.add("Answer: did the Stock Balance show last night's count plus the load?");
            }
            if (d.input() == Input.STOCK_COUNT && lv.tabletStockAfter() == null) out.add("Answer: did the tablet show the remaining stock after sales?");
        }
        if (d.input() == Input.BOOK) {
            BookView bv = control.book(date);
            for (CheckView c : bv.checks()) {
                if (ControlChecks.FAIL.equals(c.status()) && c.acknowledged() == null) out.add(c.title() + ": " + c.detail() + " (the owner can acknowledge it on the dashboard)");
                else if (ControlChecks.WAITING.equals(c.status())) out.add(c.detail());
            }
            List<String> open = new ArrayList<>();
            for (Instance other : dayInstances) {
                if (other.def().code().equals(d.code()) || !counted(other.def()) || other.def().freq() != Freq.DAILY) continue;
                if (!isDone(other, date, range)) open.add(other.def().title() + (other.vehicle().isEmpty() ? "" : " (" + other.vehicle() + ")"));
            }
            if (!open.isEmpty()) out.add("Finish these first: " + String.join(", ", open));
        }
        if (d.code().equals("M_ALLDAYS")) {
            int days = 0;
            LocalDate first = YearMonth.from(date).atDay(1).isBefore(startDate) ? startDate : YearMonth.from(date).atDay(1);
            Range month = new Range(first, date);
            for (LocalDate x = first; x.isBefore(date); x = x.plusDays(1)) {
                boolean openDay = false;
                for (Instance o : instances(x, month)) if (counted(o.def()) && !isDone(o, x, month)) { openDay = true; break; }
                if (openDay) days++;
            }
            if (days > 0) out.add(days + (days == 1 ? " day of this month still has" : " days of this month still have") + " open tasks (see the calendar).");
        }
        if (d.code().equals("M_MONTHEND")) {
            boolean allDays = range.of(date).stream().anyMatch(r -> r.getStatus().equals(ControlTaskDone.DONE) && r.getTaskCode().equals("M_ALLDAYS"));
            if (!allDays) out.add("Confirm \"Every day of the month is complete\" first.");
            Range month = new Range(YearMonth.from(date).atDay(1), date);
            long unsigned = month.books.values().stream().filter(b -> b.getSignedBy() == null).count();
            if (unsigned > 0) out.add(unsigned + " day(s) of this month are not signed by the owner.");
        }
        return new ArrayList<>(new java.util.LinkedHashSet<>(out));
    }

    // ---------------------------------------------------------------- confirming, reopening, not running

    @Transactional
    public DayTasks confirm(LocalDate date, DoneRequest request, String login) {
        if (request == null) throw new IllegalArgumentException("Nothing to confirm.");
        Def def = ControlTaskDefs.find(request.taskCode());
        if (def == null) throw new IllegalArgumentException("That task does not exist.");
        if (def.input() == Input.SIGN) throw new IllegalArgumentException("Only the owner signs a day, on the Control dashboard.");
        String by = clean(request.doneBy(), 100);
        if (by == null) throw new IllegalArgumentException("Write your name: who did this?");
        String note = clean(request.note(), 500);
        String vehicle = def.scope() == Scope.LORRY ? ControlVehicles.find(request.vehicle()).code() : "";
        LocalDate today = BusinessTime.today();
        if (date == null || date.isAfter(today)) throw new IllegalArgumentException("That day has not come yet.");
        if (date.isBefore(startDate)) throw new IllegalArgumentException("Tasks start on " + startDate + ".");
        Range range = new Range(date, date);
        List<Instance> inst = instances(date, range);
        Instance target = inst.stream().filter(i -> i.def().code().equals(def.code()) && i.vehicle().equals(vehicle)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That task is not on this day."));
        if (isDone(target, date, range)) throw new IllegalArgumentException("That task is already confirmed.");
        if (def.input() == Input.CONFIRM && note == null) throw new IllegalArgumentException("Write in the note what you did or compared.");
        List<String> blockers = blockersOf(target, date, inst, range, new HashMap<>());
        if (!blockers.isEmpty()) throw new IllegalArgumentException("It cannot be confirmed yet: " + String.join(" ", blockers));
        done.save(new ControlTaskDone(date, def.code(), vehicle, ControlTaskDone.DONE, by, login, note));
        audit.record("CONTROL_TASK_DONE", "ControlTask", null, date + " " + def.code() + (vehicle.isEmpty() ? "" : " " + vehicle) + " confirmed by " + by);
        return day(date);
    }

    @Transactional
    public DayTasks reopen(LocalDate date, String taskCode, String vehicle, String login) {
        Def def = ControlTaskDefs.find(taskCode);
        if (def == null) throw new IllegalArgumentException("That task does not exist.");
        String v = def.scope() == Scope.LORRY ? ControlVehicles.find(vehicle).code() : "";
        ControlTaskDone row = done.findByControlDateAndTaskCodeAndVehicleCode(date, def.code(), v).orElseThrow(() -> new IllegalArgumentException("That task is not confirmed."));
        done.delete(row);
        audit.record("CONTROL_TASK_REOPEN", "ControlTask", null, date + " " + def.code() + (v.isEmpty() ? "" : " " + v) + " reopened by " + login + " (was confirmed by " + row.getDoneBy() + ")");
        return day(date);
    }

    /** The lorry did not run that day: it has no tasks. Not allowed when bills were already written for it. */
    @Transactional
    public DayTasks setNoRun(LocalDate date, String vehicleCode, boolean noRun, String doneBy, String login) {
        Vehicle v = ControlVehicles.find(vehicleCode);
        if (date == null || date.isAfter(BusinessTime.today())) throw new IllegalArgumentException("That day has not come yet.");
        var existing = done.findByControlDateAndTaskCodeAndVehicleCode(date, ControlTaskDone.NO_RUN, v.code());
        if (noRun) {
            String by = clean(doneBy, 100);
            if (by == null) throw new IllegalArgumentException("Write your name: who says it did not run?");
            if (lorryDays.findByControlDateAndVehicleCode(date, v.code()).map(d -> !d.getBills().isEmpty()).orElse(false)) throw new IllegalArgumentException(v.code() + " already has bills on this day, so it did run.");
            if (existing.isEmpty()) done.save(new ControlTaskDone(date, ControlTaskDone.NO_RUN, v.code(), ControlTaskDone.NO_RUN, by, login, null));
            audit.record("CONTROL_NO_RUN", "ControlTask", null, date + " " + v.code() + " did not run, said by " + by);
        } else {
            existing.ifPresent(done::delete);
            audit.record("CONTROL_NO_RUN_UNDO", "ControlTask", null, date + " " + v.code() + " runs again, by " + login);
        }
        return day(date);
    }

    /** A Sunday or poya day the agency worked: opens its tasks. */
    @Transactional
    public DayTasks openDay(LocalDate date, String doneBy, String login) {
        if (date == null || date.isAfter(BusinessTime.today())) throw new IllegalArgumentException("That day has not come yet.");
        String by = clean(doneBy, 100);
        if (by == null) throw new IllegalArgumentException("Write your name: who says the agency worked?");
        if (done.findByControlDateAndTaskCodeAndVehicleCode(date, ControlTaskDone.OPEN_DAY, "").isEmpty()) {
            done.save(new ControlTaskDone(date, ControlTaskDone.OPEN_DAY, "", ControlTaskDone.OPEN_DAY, by, login, null));
            audit.record("CONTROL_OPEN_DAY", "ControlTask", null, date + " worked although " + control.closedReason(date) + ", said by " + by);
        }
        return day(date);
    }

    @Transactional
    public void setLorryActive(String vehicleCode, boolean active, String login) {
        Vehicle v = ControlVehicles.find(vehicleCode);
        ControlLorrySetting s = settings.findById(v.code()).orElseGet(() -> new ControlLorrySetting(v.code()));
        s.set(active, login);
        settings.save(s);
        audit.record("CONTROL_LORRY_ACTIVE", "ControlLorrySetting", null, v.code() + (active ? " is in use" : " is switched off") + ", by " + login);
    }

    @Transactional(readOnly = true)
    public Map<String, Boolean> lorryActive() {
        Set<String> off = settings.findAll().stream().filter(s -> !s.isActive()).map(ControlLorrySetting::getVehicleCode).collect(Collectors.toSet());
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Vehicle v : ControlVehicles.ALL) out.put(v.code(), !off.contains(v.code()));
        return out;
    }

    // ---------------------------------------------------------------- the lists typed into Connect Plus

    @Transactional(readOnly = true)
    public EntriesView entries(LocalDate date, String kind, String vehicle) {
        ControlTaskDefs.EntryKind k = kindOf(kind);
        String v = vehicleOf(vehicle);
        List<EntryLine> lines = entries.findByControlDateAndKindAndVehicleCodeOrderByLineNo(date, k.code(), v).stream()
                .map(e -> new EntryLine(e.getColA(), e.getColB(), e.getQty(), e.getAmount())).toList();
        return new EntriesView(date, v, k.code(), k.title(), k.columns(), lines);
    }

    @Transactional
    public EntriesView saveEntries(LocalDate date, String kind, String vehicle, List<EntryLine> lines, String login) {
        ControlTaskDefs.EntryKind k = kindOf(kind);
        String v = vehicleOf(vehicle);
        if (date == null || date.isAfter(BusinessTime.today())) throw new IllegalArgumentException("That day has not come yet.");
        if (date.isBefore(startDate)) throw new IllegalArgumentException("Tasks start on " + startDate + ".");
        List<EntryLine> in = lines == null ? List.of() : lines;
        if (in.size() > 300) throw new IllegalArgumentException("A list holds at most 300 lines.");
        entries.deleteByControlDateAndKindAndVehicleCode(date, k.code(), v);
        entries.flush();
        int n = 0;
        for (EntryLine l : in) {
            String a = clean(l.a(), 200), b = clean(l.b(), 200);
            if (a == null && b == null && l.qty() == null && l.amount() == null) continue;
            if (a == null) throw new IllegalArgumentException("Line " + (n + 1) + " of " + k.title() + " has numbers but no " + k.columns().get(0).label().toLowerCase() + ".");
            if (l.qty() != null && l.qty() < 0) throw new IllegalArgumentException("Packs cannot be negative (" + a + ").");
            if (l.amount() != null && (l.amount().signum() < 0 || l.amount().compareTo(new BigDecimal("999999999.99")) > 0)) throw new IllegalArgumentException("Check the amount of " + a + ".");
            entries.save(new ControlEntry(date, v, k.code(), ++n, a, b, l.qty(), l.amount() == null ? null : l.amount().setScale(2, java.math.RoundingMode.HALF_UP)));
        }
        audit.record("CONTROL_ENTRIES", "ControlEntry", null, date + " " + k.code() + (v.isEmpty() ? "" : " " + v) + ": " + n + " lines, by " + login);
        return entries(date, k.code(), v);
    }

    private static ControlTaskDefs.EntryKind kindOf(String kind) {
        ControlTaskDefs.EntryKind k = kind == null ? null : ControlTaskDefs.ENTRY_KINDS.get(kind.toUpperCase());
        if (k == null) throw new IllegalArgumentException("Unknown list.");
        return k;
    }

    private static String vehicleOf(String vehicle) {
        return vehicle == null || vehicle.isBlank() ? "" : ControlVehicles.find(vehicle).code();
    }

    private static String clean(String text, int max) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new IllegalArgumentException("\"" + t.substring(0, 20) + "...\" is too long.");
        return t;
    }

    // ---------------------------------------------------------------- the dashboard in numbers

    @Transactional(readOnly = true)
    public Numbers numbers(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        if (to == null || to.isAfter(today)) to = today;
        if (from == null || from.isBefore(startDate)) from = startDate;
        if (to.isBefore(from)) throw new IllegalArgumentException("Choose dates from " + startDate + " up to today.");
        if (from.plusDays(62).isBefore(to)) throw new IllegalArgumentException("Choose at most 62 days at a time.");

        Map<LocalDate, int[]> dayBills = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal[]> dayMoney = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) { dayBills.put(d, new int[1]); dayMoney.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}); }
        Map<String, BigDecimal[]> lorryMoney = new LinkedHashMap<>();
        Map<String, int[]> lorryCount = new LinkedHashMap<>();
        for (Vehicle v : ControlVehicles.ALL) { lorryMoney.put(v.code(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}); lorryCount.put(v.code(), new int[2]); }
        int bills = 0;
        BigDecimal value = BigDecimal.ZERO, cash = BigDecimal.ZERO, cheque = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (ControlLorryDay day : lorryDays.findByControlDateBetween(from, to)) {
            if (day.getBills().isEmpty()) continue;
            BigDecimal[] lm = lorryMoney.get(day.getVehicleCode());
            int[] lc = lorryCount.get(day.getVehicleCode());
            lc[0]++;
            for (ControlBillLine b : day.getBills()) {
                BigDecimal bv = z(b.getBillValue()), bc = z(b.getCashRep()).add(z(b.getCashDriver())), bq = z(b.getChequeAmount()), br = z(b.getCreditAmount());
                bills++; lc[1]++; dayBills.get(day.getControlDate())[0]++;
                value = value.add(bv); cash = cash.add(bc); cheque = cheque.add(bq); credit = credit.add(br);
                BigDecimal[] dm = dayMoney.get(day.getControlDate());
                dm[0] = dm[0].add(bv); dm[1] = dm[1].add(bc); dm[2] = dm[2].add(bq); dm[3] = dm[3].add(br);
                lm[0] = lm[0].add(bv); lm[1] = lm[1].add(bc); lm[2] = lm[2].add(bq); lm[3] = lm[3].add(br);
            }
        }
        BigDecimal oldCredit = BigDecimal.ZERO, chequesReceived = BigDecimal.ZERO, cancelledValue = BigDecimal.ZERO;
        int cancelled = 0, damaged = 0;
        for (ControlEntry e : entries.findByControlDateBetween(from, to)) {
            switch (e.getKind()) {
                case ControlTaskDefs.OLD_CREDIT -> oldCredit = oldCredit.add(z(e.getAmount()));
                case ControlTaskDefs.CHEQUE -> chequesReceived = chequesReceived.add(z(e.getAmount()));
                case ControlTaskDefs.CANCEL -> { cancelled++; cancelledValue = cancelledValue.add(z(e.getAmount())); }
                case ControlTaskDefs.DAMAGED -> damaged += e.getQty() == null ? 0 : e.getQty();
                default -> { }
            }
        }
        List<LorryNumbers> lorries = new ArrayList<>();
        for (Vehicle v : ControlVehicles.ALL) {
            BigDecimal[] m = lorryMoney.get(v.code());
            int[] c = lorryCount.get(v.code());
            if (c[0] > 0) lorries.add(new LorryNumbers(v.code(), v.vanSales(), c[0], c[1], m[0], m[1], m[2], m[3]));
        }
        List<DayNumbers> days = new ArrayList<>();
        dayBills.forEach((d, c) -> { BigDecimal[] m = dayMoney.get(d); days.add(new DayNumbers(d, c[0], m[0], m[1], m[2], m[3])); });

        // the work: how much of it is done, how much is overdue
        Range range = new Range(from, to);
        int required = 0, finished = 0, unsigned = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int r = 0, f = 0;
            for (Instance i : instances(d, range)) { if (!counted(i.def())) continue; r++; if (isDone(i, d, range)) f++; }
            required += r; finished += f;
            ControlDayBook book = range.books.get(d);
            if (r > 0 && r == f && d.isBefore(today) && (book == null || book.getSignedBy() == null)) unsigned++;
        }
        Progress p = progress();
        int percent = required == 0 ? 100 : (int) Math.round(finished * 100.0 / required);
        TaskNumbers tasks = new TaskNumbers(required, finished, percent, p.overdueTasks(), p.overdueDays(), p.oldestOverdue(), p.todayRequired(), p.todayDone());

        // what does not agree, from the checks
        List<DaySummary> board = control.board(from, to);
        int failing = 0, tablet = 0, stock = 0;
        List<FocusItem> focus = new ArrayList<>();
        if (p.overdueTasks() > 0) {
            focus.add(new FocusItem("DANGER", p.overdueTasks() + " tasks are overdue on " + p.overdueDays() + (p.overdueDays() == 1 ? " day" : " days"),
                    "Oldest: " + p.oldestOverdue() + ". Redo the days in order, oldest first.", "/control?date=" + p.oldestOverdue()));
        }
        for (DaySummary ds : board) {
            for (var l : ds.lorries()) {
                for (CheckView c : l.problems()) {
                    if (c.acknowledged() != null) continue;
                    failing++;
                    if (focus.stream().filter(f -> f.title().contains(" · ")).count() < 8) focus.add(new FocusItem("DANGER", l.vehicle() + " · " + ds.date() + ": " + c.title(), c.detail(), "/control/dashboard?day=" + ds.date()));
                    if (l.vanSales() && c.code().equals(ControlChecks.TABLET)) tablet++;
                    if (l.vanSales() && c.code().equals(ControlChecks.STOCK)) stock++;
                }
            }
            for (CheckView c : ds.book().checks()) if (ControlChecks.FAIL.equals(c.status()) && c.acknowledged() == null) { failing++; focus.add(new FocusItem("DANGER", "The safe · " + ds.date() + ": " + c.title(), c.detail(), "/control/dashboard?day=" + ds.date())); }
        }
        if (unsigned > 0) focus.add(new FocusItem("WARNING", unsigned + (unsigned == 1 ? " day is" : " days are") + " complete but not signed by you", "Open the dashboard days and sign the ones you have checked.", "/control/dashboard"));
        if (tablet > 0) focus.add(new FocusItem("WARNING", "The van's tablet or morning stock was wrong on " + tablet + (tablet == 1 ? " day" : " days"), "Raise it with CBL support with the dates.", "/control/dashboard"));
        if (stock > 0) focus.add(new FocusItem("WARNING", "The van's stock count differed from the system on " + stock + (stock == 1 ? " day" : " days"), "Look at the products that differ and at the load.", "/control/dashboard"));
        if (p.todayRequired() > p.todayDone()) focus.add(new FocusItem("INFO", "Today: " + p.todayDone() + " of " + p.todayRequired() + " tasks done", "Open the calendar for today.", "/control"));
        if (credit.signum() > 0 || oldCredit.signum() > 0) focus.add(new FocusItem("INFO", "Credit given " + money(credit) + ", old credit collected " + money(oldCredit), "Credit still owed grows by the difference. Chase the shops on the weekly list.", "/control"));
        Totals totals = new Totals(bills, value, cash, cheque, credit, oldCredit, chequesReceived, cancelled, cancelledValue, damaged);
        return new Numbers(from, to, startDate, totals, lorries, days, tasks, failing, tablet, stock, unsigned, focus);
    }

    private static BigDecimal z(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String money(BigDecimal v) { return String.format(java.util.Locale.ENGLISH, "%,.2f", v); }
}
