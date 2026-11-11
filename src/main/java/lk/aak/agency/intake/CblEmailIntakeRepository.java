package lk.aak.agency.intake;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CblEmailIntakeRepository extends JpaRepository<CblEmailIntake, Long> {

    Optional<CblEmailIntake> findFirstByFileHashAndStatusOrderByIdDesc(String fileHash, String status);

    Optional<CblEmailIntake> findByMessageKey(String messageKey);

    long countByStatusAndResolvedFalse(String status);

    List<CblEmailIntake> findByStatusAndResolvedFalseOrderByIdDesc(String status);

    List<CblEmailIntake> findAllByOrderByIdDesc(Pageable pageable);

    Optional<CblEmailIntake> findFirstByStatusOrderByIdDesc(String status);

    long countByStatus(String status);

    long countByStatusAndResolvedTrue(String status);

    /**
     * The CBL e-mail list. filter: all | attention (needs a person) | imported | handled (was refused, a person dealt with it) | empty.
     * pattern is a lower-case LIKE pattern ("%" for everything).
     */
    @Query("""
            select e from CblEmailIntake e
            where (:filter = 'all'
                   or (:filter = 'attention' and e.status = 'NEEDS_ATTENTION' and e.resolved = false)
                   or (:filter = 'imported' and e.status = 'IMPORTED')
                   or (:filter = 'handled' and e.status = 'NEEDS_ATTENTION' and e.resolved = true)
                   or (:filter = 'empty' and e.status = 'EMPTY'))
              and (lower(coalesce(e.emailSubject, '')) like :pattern
                   or lower(coalesce(e.fileName, '')) like :pattern
                   or lower(coalesce(e.documentNumber, '')) like :pattern
                   or lower(coalesce(e.emailSender, '')) like :pattern)
            """)
    Page<CblEmailIntake> search(@Param("filter") String filter, @Param("pattern") String pattern, Pageable pageable);
}
