package lk.aak.agency.service;

import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
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
 * by id, then customer, so overlapping requests cannot deadlock.
 */
@Service
public class RowLockService {

    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;

    public RowLockService(
            ProductRepository productRepository,
            CustomerRepository customerRepository,
            SalesInvoiceRepository salesInvoiceRepository) {

        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
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
            salesInvoiceRepository.lockById(invoiceId);
        }
    }
}
