package lk.aak.agency.repository;

import lk.aak.agency.model.SalesInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SalesInvoiceItemRepository
        extends JpaRepository<SalesInvoiceItem, Long> {

    List<SalesInvoiceItem>
    findBySalesInvoiceIdOrderByIdAsc(Long salesInvoiceId);

    boolean existsByProductId(Long productId);

    void deleteBySalesInvoiceId(Long salesInvoiceId);

    /*
     * Dashboard: completed sales by product category, as (category, amount). Products with no category group
     * together. A line's share is its amount after the bill's discount and return are spread over the lines in
     * proportion (line amount x net / gross), so the categories add up to the same NET sales the rest of the
     * dashboard shows (not the gross, before-discount figure).
     */
    @Query("""
            SELECT COALESCE(NULLIF(TRIM(p.category), ''), 'Uncategorised'),
                COALESCE(SUM(CASE WHEN i.grossAmount > 0 THEN it.amount * i.netAmount / i.grossAmount ELSE 0 END), 0)
            FROM SalesInvoiceItem it JOIN it.salesInvoice i LEFT JOIN i.customer c LEFT JOIN it.product p
            WHERE i.status = 'COMPLETED' AND i.invoiceDate BETWEEN :from AND :to
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId OR i.salesRepEmployeeId = :employeeId)
            GROUP BY COALESCE(NULLIF(TRIM(p.category), ''), 'Uncategorised')
            """)
    List<Object[]> completedSalesByCategory(
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("employeeId") Long employeeId);

    /**
     * How much of each product other people's DRAFT bills already ask for (nothing is taken from stock until a bill is
     * completed, so the bill screen uses this to warn that the stock shown is already spoken for). Drafts older than
     * `since` are ignored so a forgotten draft does not hold stock for ever; `excludeInvoiceId` leaves out the bill
     * being edited. Rows: product id, total pieces, number of pending bills.
     */
    @Query("""
            SELECT it.product.id, COALESCE(SUM(it.quantity), 0), COUNT(DISTINCT i.id)
            FROM SalesInvoiceItem it JOIN it.salesInvoice i
            WHERE i.status = 'DRAFT' AND i.invoiceDate >= :since
                AND (:excludeInvoiceId IS NULL OR i.id <> :excludeInvoiceId)
            GROUP BY it.product.id
            """)
    List<Object[]> pendingDraftQuantities(@Param("since") LocalDate since, @Param("excludeInvoiceId") Long excludeInvoiceId);
}
