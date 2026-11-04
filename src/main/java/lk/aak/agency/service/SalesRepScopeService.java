package lk.aak.agency.service;

import lk.aak.agency.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * ADMIN and OFFICE see every customer/invoice/payment. Every other role (SALES_REP, and any
 * unexpected or mistyped role) is scoped to only the customers assigned to them - resolved via
 * Employee.systemUsername matching the logged-in username. A login with no linked employee
 * record, or one shared by several employee records, sees nothing (fail closed) rather than
 * falling back to unscoped access.
 */
@Service
public class SalesRepScopeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SalesRepScopeService.class);

    // No real customer/employee ever has this id - used to force an empty result set for a
    // SALES_REP whose login isn't linked to any employee record yet.
    public static final Long NO_ACCESS_SENTINEL = -1L;

    private final EmployeeRepository employeeRepository;

    public SalesRepScopeService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    /**
     * @return null only if the current user is ADMIN or OFFICE (sees everything), otherwise the
     * employee id their view must be scoped to (NO_ACCESS_SENTINEL if there is none, or if it is
     * ambiguous).
     */
    public Long resolveScopedEmployeeId(Authentication authentication) {
        boolean seesEverything = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")
                        || authority.getAuthority().equals("ROLE_OFFICE"));

        if (seesEverything) {
            return null;
        }

        var linked = employeeRepository.findAllBySystemUsernameIgnoreCase(authentication.getName());

        if (linked.size() > 1) {
            // Two employee records claim one login (old data). Guessing could show another
            // person's shops, so show nothing until the owner fixes the duplicate.
            LOGGER.warn("Login \"{}\" is linked to {} employee records; showing no shops until this is fixed.",
                    authentication.getName(), linked.size());
            return NO_ACCESS_SENTINEL;
        }

        return linked.isEmpty() ? NO_ACCESS_SENTINEL : linked.get(0).getId();
    }
}
