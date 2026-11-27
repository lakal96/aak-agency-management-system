package lk.aak.agency.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControlLorrySettingRepository extends JpaRepository<ControlLorrySetting, String> {
}
