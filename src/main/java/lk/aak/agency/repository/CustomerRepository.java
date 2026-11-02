package lk.aak.agency.repository;

import jakarta.persistence.LockModeType;
import lk.aak.agency.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByCustomerCode(String customerCode);

    boolean existsByCustomerCode(String customerCode);

    Optional<Customer> findTopByOrderByIdDesc();

    Optional<Customer> findByQrCode(String qrCode);

    long countByStatus(String status);

    long countByAssignedEmployeeId(Long assignedEmployeeId);

    /*
     * Paginated list, optionally filtered by name, code, area, contact person or phone -
     * backs the REST API's list endpoint (the Thymeleaf list page isn't searchable yet).
     * employeeId scopes results to one assigned employee (SALES_REP visibility) - pass null
     * to see everyone (ADMIN/OFFICE).
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE (:keyword IS NULL OR :keyword = ''
                OR LOWER(c.customerName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(c.area) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(c.contactPerson) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :keyword, '%')))
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId)
            """)
    Page<Customer> search(
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            Pageable pageable
    );

    /** Row-locks one Customer (FOR UPDATE) so concurrent completions are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM Customer x WHERE x.id = :id")
    Optional<Customer> lockById(@Param("id") Long id);
}
