package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.AttendanceMarkRequest;
import lk.aak.agency.dto.api.AttendanceRowResponse;
import lk.aak.agency.service.EmployeeAttendanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceApiController {

    private final EmployeeAttendanceService attendanceService;

    public AttendanceApiController(EmployeeAttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping
    public List<AttendanceRowResponse> sheet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate selectedDate = date != null ? date : LocalDate.now();

        return attendanceService.getAttendanceSheetForDate(selectedDate).stream()
                .map(AttendanceRowResponse::new)
                .toList();
    }

    @PostMapping("/mark")
    public void mark(@Valid @RequestBody AttendanceMarkRequest request) {
        attendanceService.markAttendance(request.getEmployeeId(), request.getDate(), request.getStatus(), request.getNotes());
    }
}
