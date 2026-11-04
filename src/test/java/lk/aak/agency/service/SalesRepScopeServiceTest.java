package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SalesRepScopeServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @InjectMocks private SalesRepScopeService service;

    private Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "x", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }

    @Test
    void ownerAndOfficeSeeEverything() {
        assertThat(service.resolveScopedEmployeeId(auth("a", "ADMIN"))).isNull();
        assertThat(service.resolveScopedEmployeeId(auth("o", "OFFICE"))).isNull();
    }

    @Test
    void aRepIsScopedToTheirOwnEmployeeRecord() {
        lenient().when(employeeRepository.findAllBySystemUsernameIgnoreCase("kamal")).thenReturn(List.of(employee(7L)));

        assertThat(service.resolveScopedEmployeeId(auth("kamal", "SALES_REP"))).isEqualTo(7L);
    }

    @Test
    void anyOtherRoleIsNeverGivenTheFullView() {
        for (String role : List.of("DRIVER", "FOO", "sales_rep", "ADMINISTRATOR")) {
            assertThat(service.resolveScopedEmployeeId(auth("x", role)))
                    .as(role).isEqualTo(SalesRepScopeService.NO_ACCESS_SENTINEL);
        }
    }

    @Test
    void anUnlinkedLoginSeesNothing() {
        assertThat(service.resolveScopedEmployeeId(auth("nobody", "SALES_REP")))
                .isEqualTo(SalesRepScopeService.NO_ACCESS_SENTINEL);
    }

    @Test
    void aLoginSharedByTwoEmployeesSeesNothingAndDoesNotThrow() {
        lenient().when(employeeRepository.findAllBySystemUsernameIgnoreCase("kamal"))
                .thenReturn(List.of(employee(7L), employee(8L)));

        assertThat(service.resolveScopedEmployeeId(auth("kamal", "SALES_REP")))
                .isEqualTo(SalesRepScopeService.NO_ACCESS_SENTINEL);
    }
}
