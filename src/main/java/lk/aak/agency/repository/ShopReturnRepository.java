package lk.aak.agency.repository;

import jakarta.persistence.LockModeType;
import lk.aak.agency.model.ShopReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShopReturnRepository extends JpaRepository<ShopReturn, Long> {

    List<ShopReturn> findAllByOrderByReturnDateDesc();

    List<ShopReturn> findByStatusOrderByIdDesc(String status);

    List<ShopReturn> findByCustomerIdOrderByReturnDateDesc(Long customerId);

    boolean existsByCustomerId(Long customerId);

    /** Row-locks one ShopReturn (FOR UPDATE) so a double approval cannot credit the shop twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM ShopReturn x WHERE x.id = :id")
    Optional<ShopReturn> lockById(@Param("id") Long id);
}
