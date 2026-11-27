package lk.aak.agency.controller.api;

import lk.aak.agency.control.ControlDtos.*;
import lk.aak.agency.control.ControlService;
import lk.aak.agency.control.ControlTaskDtos.BillsRequest;
import lk.aak.agency.control.ControlTaskDtos.CashRequest;
import lk.aak.agency.control.ControlTaskDtos.StockRequest;
import lk.aak.agency.control.ControlTaskDtos.TabletRequest;
import lk.aak.agency.control.ControlVehicles.Vehicle;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/** The Control portal (owner and office): type the paper figures of the day and upload the CBL reports they are checked against. */
@RestController
@RequestMapping("/api/v1/control")
public class ControlApiController {

    private final ControlService service;

    public ControlApiController(ControlService service) {
        this.service = service;
    }

    @GetMapping("/vehicles")
    public List<Vehicle> vehicles() {
        return service.vehicles();
    }

    @GetMapping("/days/{date}")
    public DaySummary day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(date);
    }

    @GetMapping("/days/{date}/lorries/{vehicle}")
    public LorryView lorry(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle) {
        return service.lorry(date, vehicle);
    }

    @PutMapping("/days/{date}/lorries/{vehicle}")
    public LorryView saveLorry(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                               @RequestBody LorryRequest request, Principal principal) {
        return service.saveLorry(date, vehicle, request, name(principal));
    }

    // each task form saves one part of the lorry's file

    @PutMapping("/days/{date}/lorries/{vehicle}/bills")
    public LorryView saveBills(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                               @RequestBody BillsRequest request, Principal principal) {
        return service.saveBills(date, vehicle, request.repName(), request.driverName(), request.bills(), name(principal));
    }

    @PutMapping("/days/{date}/lorries/{vehicle}/cash")
    public LorryView saveCash(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                              @RequestBody CashRequest request, Principal principal) {
        return service.saveCash(date, vehicle, request.cashCountedRep(), request.cashCountedDriver(), name(principal));
    }

    @PutMapping("/days/{date}/lorries/{vehicle}/tablet")
    public LorryView saveTablet(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                                @RequestBody TabletRequest request, Principal principal) {
        return service.saveTablet(date, vehicle, request.tabletStockMorning(), request.morningStockOk(), request.tabletStockAfter(), name(principal));
    }

    @PutMapping("/days/{date}/lorries/{vehicle}/stock")
    public LorryView saveStock(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                               @RequestBody StockRequest request, Principal principal) {
        return service.saveStock(date, vehicle, request.stock(), request.tabletStockAfter(), name(principal));
    }

    @PostMapping("/days/{date}/lorries/{vehicle}/uploads")
    public ResponseEntity<UploadView> upload(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                                             @RequestParam String kind, @RequestParam MultipartFile file, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(date, vehicle, kind, file, name(principal)));
    }

    @DeleteMapping("/days/{date}/lorries/{vehicle}/uploads/{id}")
    public ResponseEntity<Void> deleteUpload(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String vehicle,
                                             @PathVariable Long id, Principal principal) {
        service.deleteUpload(date, vehicle, id, name(principal));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/days/{date}/book")
    public BookView book(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.book(date);
    }

    @PutMapping("/days/{date}/book")
    public BookView saveBook(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestBody BookRequest request, Principal principal) {
        return service.saveBook(date, request, name(principal));
    }

    static String name(Principal principal) {
        return principal == null ? "unknown" : principal.getName();
    }
}
