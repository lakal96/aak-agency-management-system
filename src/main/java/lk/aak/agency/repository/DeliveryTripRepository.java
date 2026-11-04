package lk.aak.agency.repository;

import lk.aak.agency.model.DeliveryTrip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryTripRepository extends JpaRepository<DeliveryTrip, Long> {

    List<DeliveryTrip> findByStatusOrderByTripDateAsc(String status);

    /** Row-locks one trip (FOR UPDATE) so assigning bills, loading and closing cannot overlap. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DeliveryTrip t WHERE t.id = :id")
    Optional<DeliveryTrip> lockById(@Param("id") Long id);
}
