package lk.aak.agency.repository;

import jakarta.persistence.LockModeType;
import lk.aak.agency.model.SalesInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SalesInvoiceRepository
        extends JpaRepository<SalesInvoice, Long> {

    Optional<SalesInvoice> findByInvoiceNumber(
            String invoiceNumber
    );

    boolean existsByInvoiceNumber(
            String invoiceNumber
    );

    List<SalesInvoice>
    findAllByOrderByInvoiceDateDesc();

    /*
     * All invoices for customers assigned to one employee - backs the dashboard's
     * SALES_REP-scoped totals/outstanding-credit/overdue calculations.
     */
    List<SalesInvoice>
    findByCustomer_AssignedEmployeeId(
            Long employeeId
    );

    /*
     * Paginated list, optionally filtered by invoice number, customer name/area,
     * route code, sale type or status - keeps the list screen usable at scale.
     * employeeId scopes results to one assigned employee's customers (SALES_REP
     * visibility) - pass null to see everyone (ADMIN/OFFICE).
     */
    @Query("""
            SELECT i FROM SalesInvoice i
            LEFT JOIN i.customer c
            WHERE (:keyword IS NULL OR :keyword = ''
                OR LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(c.customerName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(c.area) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.routeCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.saleType) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.status) LIKE LOWER(CONCAT('%', :keyword, '%')))
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId OR i.salesRepEmployeeId = :employeeId)
            """)
    Page<SalesInvoice> search(
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            Pageable pageable
    );

    /*
     * Returns all sales invoices belonging to one customer.
     */
    List<SalesInvoice>
    findByCustomerIdOrderByInvoiceDateDesc(
            Long customerId
    );

    boolean existsByCustomerId(Long customerId);

    /*
     * Completed bills not yet assigned to any delivery trip - eligible for loading.
     */
    List<SalesInvoice> findByStatusAndDeliveryTripIdIsNullOrderByInvoiceDateAsc(String status);

    /*
     * Bills currently assigned to a given delivery trip.
     */
    List<SalesInvoice> findByDeliveryTripIdOrderByIdAsc(Long deliveryTripId);

    @Query("SELECT COALESCE(SUM(i.netAmount), 0) FROM SalesInvoice i WHERE i.status = 'COMPLETED' AND i.invoiceDate BETWEEN :from AND :to")
    BigDecimal sumNetAmountBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /*
     * Dashboard building blocks. Only COMPLETED bills count as sales (drafts are not). employeeId scopes the
     * result to one rep's shops - pass null for the whole agency (ADMIN/OFFICE).
     */
    @Query("""
            SELECT i.invoiceDate, COUNT(i), COALESCE(SUM(i.netAmount), 0)
            FROM SalesInvoice i LEFT JOIN i.customer c
            WHERE i.status = 'COMPLETED' AND i.invoiceDate BETWEEN :from AND :to
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId OR i.salesRepEmployeeId = :employeeId)
            GROUP BY i.invoiceDate
            """)
    List<Object[]> completedTotalsByDay(
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("employeeId") Long employeeId);

    @Query("""
            SELECT DISTINCT i.invoiceDate, c.id
            FROM SalesInvoice i JOIN i.customer c
            WHERE i.status = 'COMPLETED' AND i.invoiceDate BETWEEN :from AND :to
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId OR i.salesRepEmployeeId = :employeeId)
            """)
    List<Object[]> buyingCustomersByDay(
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("employeeId") Long employeeId);

    /* Bills on trips dated :day, as (bill delivery status, trip status, count). */
    @Query("""
            SELECT i.deliveryStatus, t.status, COUNT(i)
            FROM SalesInvoice i JOIN DeliveryTrip t ON t.id = i.deliveryTripId LEFT JOIN i.customer c
            WHERE t.tripDate = :day
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId OR i.salesRepEmployeeId = :employeeId)
            GROUP BY i.deliveryStatus, t.status
            """)
    List<Object[]> deliveryCountsForDay(@Param("day") LocalDate day, @Param("employeeId") Long employeeId);

    long countByPaymentStatusIn(List<String> paymentStatuses);

    List<SalesInvoice> findByPaymentStatusIn(List<String> paymentStatuses);

    /**
     * Every COMPLETED bill that still has something to pay, with its shop and the money received so far, as
     * {bill, shop, received}. One query for the whole credit follow-up (it used to ask once per shop, then once more
     * per bill). "Received" counts only payments in status RECEIVED, exactly like PaymentRepository.calculatePaidAmount.
     */
    @Query("""
            SELECT i, c, COALESCE((SELECT SUM(p.amount) FROM Payment p
                                   WHERE p.salesInvoice = i AND UPPER(p.status) = 'RECEIVED'), 0)
            FROM SalesInvoice i JOIN i.customer c
            WHERE UPPER(i.status) = 'COMPLETED'
              AND COALESCE(i.netAmount, 0) > COALESCE((SELECT SUM(p2.amount) FROM Payment p2
                                   WHERE p2.salesInvoice = i AND UPPER(p2.status) = 'RECEIVED'), 0)
            """)
    List<Object[]> findOpenBalances();

    /** Row-locks one SalesInvoice (FOR UPDATE) so concurrent completions are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM SalesInvoice x WHERE x.id = :id")
    Optional<SalesInvoice> lockById(@Param("id") Long id);
}
