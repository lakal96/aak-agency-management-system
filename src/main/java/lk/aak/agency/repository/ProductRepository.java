package lk.aak.agency.repository;

import lk.aak.agency.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository
        extends JpaRepository<Product, Long> {

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