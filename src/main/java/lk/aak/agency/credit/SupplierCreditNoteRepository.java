package lk.aak.agency.credit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SupplierCreditNoteRepository extends JpaRepository<SupplierCreditNote, Long> {

    Optional<SupplierCreditNote> findByDocumentNumber(String documentNumber);

    List<SupplierCreditNote> findAllByOrderByNoteDateDescIdDesc();

    /** Locks the row, so two people approving or deleting the same note queue up and the second sees the result. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SupplierCreditNote c WHERE c.id = :id")
    Optional<SupplierCreditNote> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM SupplierCreditNote c WHERE c.status = 'APPROVED'")
    BigDecimal approvedTotal();
}
