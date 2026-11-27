package lk.aak.agency.controller.api;

import lk.aak.agency.control.ControlDtos.*;
import lk.aak.agency.control.ControlService;
import lk.aak.agency.control.ControlTaskDtos.Numbers;
import lk.aak.agency.control.ControlTaskService;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/** The Control dashboard (owner only): every day at a glance, what does not agree, and the owner's acknowledgement and signature. */
@RestController
@RequestMapping("/api/v1/control/dashboard")
public class ControlDashboardApiController {

    private final ControlService service;
    private final ControlTaskService tasks;

    public ControlDashboardApiController(ControlService service, ControlTaskService tasks) {
        this.service = service;
        this.tasks = tasks;
    }

    /** The business in numbers for a period, and where to focus. */
    @GetMapping("/numbers")
    public Numbers numbers(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return tasks.numbers(from, to);
    }

    @GetMapping("/lorries")
    public Map<String, Boolean> lorries() {
        return tasks.lorryActive();
    }

    /** Switch a lorry off (it has no tasks) or on again. */
    @PutMapping("/lorries/{vehicle}/active")
    public Map<String, Boolean> setActive(@PathVariable String vehicle, @RequestParam boolean active, Principal principal) {
        tasks.setLorryActive(vehicle, active, ControlApiController.name(principal));
        return tasks.lorryActive();
    }

    @GetMapping("/board")
    public List<DaySummary> board(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.board(from, to);
    }

    @GetMapping("/days/{date}/lorries/{vehicle}")
    public LorryView lorry(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle) {
        return service.lorry(date, vehicle);
    }

    @PostMapping("/days/{date}/lorries/{vehicle}/acknowledge")
    public LorryView acknowledge(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                                 @RequestBody ClosureRequest request, Principal principal) {
        return service.close(date, vehicle, request.checkCode(), request.note(), ControlApiController.name(principal));
    }

    @PostMapping("/days/{date}/book/acknowledge")
    public BookView acknowledgeBook(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestBody ClosureRequest request, Principal principal) {
        return service.closeBook(date, request.checkCode(), request.note(), ControlApiController.name(principal));
    }

    @PostMapping("/days/{date}/sign")
    public BookView sign(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Principal principal) {
        return service.sign(date, ControlApiController.name(principal));
    }
}
