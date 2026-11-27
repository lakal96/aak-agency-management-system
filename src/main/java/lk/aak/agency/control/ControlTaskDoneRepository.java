package lk.aak.agency.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControlTaskDoneRepository extends JpaRepository<ControlTaskDone, Long> {
    List<ControlTaskDone> findByControlDate(LocalDate date);
    List<ControlTaskDone> findByControlDateBetween(LocalDate from, LocalDate to);
    Optional<ControlTaskDone> findByControlDateAndTaskCodeAndVehicleCode(LocalDate date, String taskCode, String vehicleCode);
}
