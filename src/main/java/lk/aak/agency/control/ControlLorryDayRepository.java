package lk.aak.agency.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControlLorryDayRepository extends JpaRepository<ControlLorryDay, Long> {
    Optional<ControlLorryDay> findByControlDateAndVehicleCode(LocalDate date, String vehicleCode);
    List<ControlLorryDay> findByControlDate(LocalDate date);
    List<ControlLorryDay> findByControlDateBetween(LocalDate from, LocalDate to);
}
