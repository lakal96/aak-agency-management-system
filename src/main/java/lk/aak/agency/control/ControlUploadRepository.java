package lk.aak.agency.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControlUploadRepository extends JpaRepository<ControlUpload, Long> {
    List<ControlUpload> findByControlDateAndVehicleCodeOrderByUploadedAtDescIdDesc(LocalDate date, String vehicleCode);
    List<ControlUpload> findByControlDateBetween(LocalDate from, LocalDate to);
}
