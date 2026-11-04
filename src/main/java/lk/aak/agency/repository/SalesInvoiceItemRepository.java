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
                AND (:employeeId IS NULL OR c.assignedEmployeeId = :employeeId)
            GROUP BY COALESCE(NULLIF(TRIM(p.category), ''), 'Uncategorised')
            """)
    List<Object[]> completedSalesByCategory(
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("employeeId") Long employeeId);
}
