package lk.aak.agency.repository;

import lk.aak.agency.model.ShopReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface ShopReturnItemRepository extends JpaRepository<ShopReturnItem, Long> {

    List<ShopReturnItem> findByShopReturnIdOrderByIdAsc(Long shopReturnId);

    boolean existsByProductId(Long productId);

    /**
     * Quantity of one product already sent back against one bill, counting the returns in the given
     * statuses (the service passes PENDING and APPROVED: a rejected return gave nothing back).
     */
    @Query("""
            SELECT COALESCE(SUM(i.quantity), 0)
            FROM ShopReturnItem i, ShopReturn r
            WHERE r.id = i.shopReturnId
                AND r.salesInvoiceId = :invoiceId
                AND i.product.id = :productId
                AND UPPER(r.status) IN :statuses
            """)
    BigDecimal sumReturnedQuantity(
            @Param("invoiceId") Long invoiceId,
            @Param("productId") Long productId,
            @Param("statuses") Collection<String> statuses);

    /**
     * Damaged / expired goods from APPROVED returns that still have to be claimed from CBL, per product
     * and category: (product id, product name, CBL code, unit, category, quantity).
     */
    @Query("""
            SELECT i.product.id, i.product.productName, i.product.cblProductCode, i.unit, i.category, SUM(i.quantity)
            FROM ShopReturnItem i, ShopReturn r
            WHERE r.id = i.shopReturnId
                AND UPPER(r.status) = 'APPROVED'
                AND i.cblClaimStatus = 'FOR_CLAIM'
            GROUP BY i.product.id, i.product.productName, i.product.cblProductCode, i.unit, i.category
            ORDER BY i.product.productName, i.category
            """)
    List<Object[]> unclaimedCblItems();
}
