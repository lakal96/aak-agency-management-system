package lk.aak.agency.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControlClosureRepository extends JpaRepository<ControlClosure, Long> {
    List<ControlClosure> findByControlDateAndVehicleCode(LocalDate date, String vehicleCode);
    List<ControlClosure> findByControlDateBetween(LocalDate from, LocalDate to);
    Optional<ControlClosure> findByControlDateAndVehicleCodeAndCheckCode(LocalDate date, String vehicleCode, String checkCode);
}
