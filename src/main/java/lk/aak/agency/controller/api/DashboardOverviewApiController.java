package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.DashboardOverviewResponse;
import lk.aak.agency.service.DashboardOverviewService;
import lk.aak.agency.service.SalesRepScopeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardOverviewApiController {

    private final DashboardOverviewService overviewService;
    private final SalesRepScopeService salesRepScopeService;

    public DashboardOverviewApiController(
            DashboardOverviewService overviewService, SalesRepScopeService salesRepScopeService) {
        this.overviewService = overviewService;
        this.salesRepScopeService = salesRepScopeService;
    }

    /** @param month optional, like 2026-11; defaults to the current month */
    @GetMapping("/overview")
    public DashboardOverviewResponse overview(
            @RequestParam(required = false) String month, Authentication authentication) {

        YearMonth shown;
        try {
            shown = month == null || month.isBlank() ? null : YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Month must look like 2026-11.");
        }

        // A sales rep only ever sees the shops assigned to them (null = everyone, for owner/office).
        return overviewService.overview(shown, salesRepScopeService.resolveScopedEmployeeId(authentication));
    }
}
