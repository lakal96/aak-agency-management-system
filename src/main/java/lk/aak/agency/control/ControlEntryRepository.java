package lk.aak.agency.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControlEntryRepository extends JpaRepository<ControlEntry, Long> {
    List<ControlEntry> findByControlDateAndKindAndVehicleCodeOrderByLineNo(LocalDate date, String kind, String vehicleCode);
    List<ControlEntry> findByControlDateBetween(LocalDate from, LocalDate to);
    List<ControlEntry> findByControlDate(LocalDate date);
    void deleteByControlDateAndKindAndVehicleCode(LocalDate date, String kind, String vehicleCode);
}
