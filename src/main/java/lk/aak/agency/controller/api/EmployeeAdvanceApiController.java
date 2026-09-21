package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.EmployeeAdvanceRequest;
import lk.aak.agency.dto.api.EmployeeAdvanceResponse;
import lk.aak.agency.service.EmployeeAdvanceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/advances")
public class EmployeeAdvanceApiController {

    private final EmployeeAdvanceService employeeAdvanceService;

    public EmployeeAdvanceApiController(EmployeeAdvanceService employeeAdvanceService) {
        this.employeeAdvanceService = employeeAdvanceService;
    }

    @GetMapping
    public List<EmployeeAdvanceResponse> list(@RequestParam(required = false) Long employeeId) {
        var advances = employeeId != null
                ? employeeAdvanceService.getAdvancesForEmployee(employeeId)
                : employeeAdvanceService.getAllAdvances();

        return advances.stream().map(EmployeeAdvanceResponse::new).toList();
    }

    @PostMapping
    public ResponseEntity<EmployeeAdvanceResponse> create(@Valid @RequestBody EmployeeAdvanceRequest request) {
        var advance = employeeAdvanceService.giveAdvance(
                request.getEmployeeId(), request.getAmount(), request.getAdvanceDate(), request.getNotes()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(new EmployeeAdvanceResponse(advance));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public EmployeeAdvanceResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeAdvanceRequest request) {
        var advance = employeeAdvanceService.updateAdvance(id, request.getAmount(), request.getAdvanceDate(), request.getNotes());
        return new EmployeeAdvanceResponse(advance);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        employeeAdvanceService.deleteAdvance(id);
        return ResponseEntity.noContent().build();
    }
}

