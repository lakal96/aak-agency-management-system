package lk.aak.agency.controller.api;

import lk.aak.agency.control.ControlDtos.*;
import lk.aak.agency.control.ControlService;
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

    public ControlDashboardApiController(ControlService service) {
        this.service = service;
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

    @PostMapping("/days/{date}/sign")
    public BookView sign(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Principal principal) {
        return service.sign(date, ControlApiController.name(principal));
    }
}
