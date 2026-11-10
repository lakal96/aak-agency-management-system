package lk.aak.agency.intake;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CblEmailIntakeRepository extends JpaRepository<CblEmailIntake, Long> {

    Optional<CblEmailIntake> findFirstByFileHashAndStatusOrderByIdDesc(String fileHash, String status);

    Optional<CblEmailIntake> findByMessageKey(String messageKey);

    long countByStatusAndResolvedFalse(String status);

    List<CblEmailIntake> findByStatusAndResolvedFalseOrderByIdDesc(String status);

    List<CblEmailIntake> findAllByOrderByIdDesc(Pageable pageable);

    Optional<CblEmailIntake> findFirstByStatusOrderByIdDesc(String status);
}
