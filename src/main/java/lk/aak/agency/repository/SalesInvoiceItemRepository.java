package lk.aak.agency.repository;

import lk.aak.agency.model.SalesInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalesInvoiceItemRepository
        extends JpaRepository<SalesInvoiceItem, Long> {

    List<SalesInvoiceItem>
    findBySalesInvoiceIdOrderByIdAsc(Long salesInvoiceId);

    boolean existsByProductId(Long productId);

    void deleteBySalesInvoiceId(Long salesInvoiceId);
}