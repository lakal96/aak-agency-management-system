package lk.aak.agency.batch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    boolean existsByPurchaseInvoiceItemId(Long purchaseInvoiceItemId);

    List<StockBatch> findByPurchaseInvoiceIdOrderByIdAsc(Long purchaseInvoiceId);

    List<StockBatch> findAllByOrderByProductIdAscBatchDateAscIdAsc();
}
