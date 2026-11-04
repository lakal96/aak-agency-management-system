package lk.aak.agency.service;

import lk.aak.agency.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.ShopReturnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.TreeSet;

/**
 * Database row locks that serialise the "check, then change" sequences in stock and credit
 * handling. Stock is a ledger (SUM of movements), so without a lock two requests can both see
 * enough stock, both pass, and both deduct - leaving negative stock.
 *
 * Callers must already be inside a transaction running at READ_COMMITTED: under MySQL's default
 * REPEATABLE READ a plain SUM taken after waiting for a lock would still read the transaction's
 * old snapshot and miss the other request's commit. Lock order is always invoice, then products
 * by id, then customer, so overlapping requests cannot deadlock. A shop return is locked before
 * the invoice it credits (nothing locks them in the opposite order).
 */
@Service
public class RowLockService {

    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final ShopReturnRepository shopReturnRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public RowLockService(
            ProductRepository productRepository,
            CustomerRepository customerRepository,
            SalesInvoiceRepository salesInvoiceRepository,
            PurchaseInvoiceRepository purchaseInvoiceRepository,
            ShopReturnRepository shopReturnRepository) {

        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.shopReturnRepository = shopReturnRepository;
    }

    /**
     * Drops a (possibly stale) copy of a record from the persistence context, so that the very
     * next lock-and-read loads the row as it is in the database NOW. A web request may already
     * hold a copy of the record read before it started waiting for the row lock (the page-open
     * entity manager spans the whole request); without this the re-read after the lock would
     * quietly return that old copy and the status check would pass on stale data.
     */
    public void forget(Object entity) {

        if (entity != null && entityManager != null && entityManager.contains(entity)) {
            entityManager.detach(entity);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProducts(Collection<Long> productIds) {

        if (productIds == null || productIds.isEmpty()) {
            return;
        }

        productRepository.lockAllByIdOrdered(new TreeSet<>(productIds));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCustomer(Long customerId) {

        if (customerId != null) {
            customerRepository.lockById(customerId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSalesInvoice(Long invoiceId) {

        if (invoiceId != null) {
            salesInvoiceRepository.lockById(invoiceId).ifPresent(this::refreshIfManaged);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPurchaseInvoice(Long invoiceId) {

        if (invoiceId != null) {
            purchaseInvoiceRepository.lockById(invoiceId).ifPresent(this::refreshIfManaged);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockShopReturn(Long shopReturnId) {

        if (shopReturnId != null) {
            shopReturnRepository.lockById(shopReturnId).ifPresent(this::refreshIfManaged);
        }
    }

    /**
     * A locking query does not overwrite a copy of the row that the persistence context already
     * holds, so reload it: whatever the caller reads next must be what the request that just
     * released the lock committed.
     */
    private void refreshIfManaged(Object entity) {

        if (entityManager != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
    }
}
