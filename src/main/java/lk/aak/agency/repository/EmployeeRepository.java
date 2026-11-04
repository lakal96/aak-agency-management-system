package lk.aak.agency.repository;

import lk.aak.agency.model.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmployeeCode(String employeeCode);

    Optional<Employee> findTopByOrderByIdDesc();

    java.util.List<Employee> findByDesignationAndStatus(String designation, String status);

    Optional<Employee> findByBiometricDeviceUserId(String biometricDeviceUserId);

    Optional<Employee> findBySystemUsername(String systemUsername);

    /** Row-locks one Employee (FOR UPDATE) so two salary payments for the same person cannot run side by side. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM Employee x WHERE x.id = :id")
    Optional<Employee> lockById(@Param("id") Long id);
}
