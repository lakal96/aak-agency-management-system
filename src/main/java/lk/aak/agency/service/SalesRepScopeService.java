package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.repository.EmployeeRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * ADMIN and OFFICE see every customer/invoice/payment. SALES_REP is scoped to only the
 * customers assigned to them - resolved via Employee.systemUsername matching the logged-in
 * username. A SALES_REP with no linked employee record sees nothing (fail closed) rather than
 * falling back to unscoped access.
 */
@Service
public class SalesRepScopeService {

    // No real customer/employee ever has this id - used to force an empty result set for a
    // SALES_REP whose login isn't linked to any employee record yet.
    public static final Long NO_ACCESS_SENTINEL = -1L;

    private final EmployeeRepository employeeRepository;

    public SalesRepScopeService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    /**
     * @return null if the current user sees everything (ADMIN/OFFICE), otherwise the
     * employee id their view must be scoped to.
     */
    public Long resolveScopedEmployeeId(Authentication authentication) {
        boolean isSalesRep = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SALES_REP"));

        if (!isSalesRep) {
            return null;
        }

        return employeeRepository.findBySystemUsername(authentication.getName())
                .map(Employee::getId)
                .orElse(NO_ACCESS_SENTINEL);
    }
}
