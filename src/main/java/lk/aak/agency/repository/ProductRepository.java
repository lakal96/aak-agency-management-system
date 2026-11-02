package lk.aak.agency.repository;

import jakarta.persistence.LockModeType;
import lk.aak.agency.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository
        extends JpaRepository<Product, Long> {

    /**
     * Row-locks the given products (FOR UPDATE), always in id order so two requests that
     * touch overlapping products can never deadlock each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id")
    List<Product> lockAllByIdOrdered(@Param("ids") Collection<Long> ids);

    Optional<Product> findByCblProductCode(
            String cblProductCode
    );

    boolean existsByCblProductCode(
            String cblProductCode
    );

    List<Product> findByProductNameContainingIgnoreCase(
            String productName
    );

    @Query("""
            SELECT p FROM Product p
            WHERE :keyword IS NULL OR :keyword = ''
                OR LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(p.cblProductCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(p.brand) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(p.category) LIKE LOWER(CONCAT('%', :keyword, '%'))
            """)
    Page<Product> search(@Param("keyword") String keyword, Pageable pageable);
}