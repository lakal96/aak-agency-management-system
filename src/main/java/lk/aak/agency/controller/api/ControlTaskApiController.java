package lk.aak.agency.controller.api;

import lk.aak.agency.control.ControlTaskDtos.*;
import lk.aak.agency.control.ControlTaskService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** The Control calendar and to-do list (owner and office): which tasks fall on a day, confirming them, and the lists typed into Connect Plus. */
@RestController
@RequestMapping("/api/v1/control/tasks")
public class ControlTaskApiController {

    private final ControlTaskService service;

    public ControlTaskApiController(ControlTaskService service) {
        this.service = service;
    }

    @GetMapping("/calendar")
    public List<CalendarDay> calendar(@RequestParam String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Choose a month, like 2026-11.");
        }
        return service.calendar(ym);
    }

    @GetMapping("/backlog")
    public Backlog backlog() {
        return service.backlog();
    }

    @GetMapping("/day/{date}")
    public DayTasks day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(date);
    }

    @PostMapping("/day/{date}/confirm")
    public DayTasks confirm(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestBody DoneRequest request, Principal principal) {
        return service.confirm(date, request, ControlApiController.name(principal));
    }

    @DeleteMapping("/day/{date}/confirm")
    public DayTasks reopen(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam String taskCode,
                           @RequestParam(required = false) String vehicle, Principal principal) {
        return service.reopen(date, taskCode, vehicle, ControlApiController.name(principal));
    }

    @PostMapping("/day/{date}/no-run")
    public DayTasks noRun(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestBody NoRunRequest request, Principal principal) {
        return service.setNoRun(date, request.vehicle(), true, request.doneBy(), ControlApiController.name(principal));
    }

    @DeleteMapping("/day/{date}/no-run")
    public DayTasks runs(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam String vehicle, Principal principal) {
        return service.setNoRun(date, vehicle, false, null, ControlApiController.name(principal));
    }

    @PostMapping("/day/{date}/open")
    public DayTasks open(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestBody NoRunRequest request, Principal principal) {
        return service.openDay(date, request.doneBy(), ControlApiController.name(principal));
    }

    @GetMapping("/entries/{date}/{kind}")
    public EntriesView entries(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String kind, @RequestParam(required = false) String vehicle) {
        return service.entries(date, kind, vehicle);
    }

    @PutMapping("/entries/{date}/{kind}")
    public EntriesView saveEntries(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String kind,
                                   @RequestParam(required = false) String vehicle, @RequestBody List<EntryLine> lines, Principal principal) {
        return service.saveEntries(date, kind, vehicle, lines, ControlApiController.name(principal));
    }
}
