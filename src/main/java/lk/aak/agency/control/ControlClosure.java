package lk.aak.agency.control;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A person looked at a check that did not agree and accepted it, with a note. The check keeps failing; it is shown as acknowledged. */
@Entity
@Table(name = "control_closure", uniqueConstraints = @UniqueConstraint(name = "uk_control_closure", columnNames = {"control_date", "vehicle_code", "check_code"}))
public class ControlClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @Column(name = "vehicle_code", nullable = false, length = 60)
    private String vehicleCode;

    @Column(name = "check_code", nullable = false, length = 40)
    private String checkCode;

    @Column(nullable = false, length = 500)
    private String note;

    @Column(name = "closed_by", nullable = false, length = 100)
    private String closedBy;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt = BusinessTime.now();

    protected ControlClosure() { }

    public ControlClosure(LocalDate controlDate, String vehicleCode, String checkCode, String note, String closedBy) {
        this.controlDate = controlDate;
        this.vehicleCode = vehicleCode;
        this.checkCode = checkCode;
        this.note = note;
        this.closedBy = closedBy;
    }

    public LocalDate getControlDate() { return controlDate; }
    public String getVehicleCode() { return vehicleCode; }
    public String getCheckCode() { return checkCode; }
    public String getNote() { return note; }
    public String getClosedBy() { return closedBy; }
    public LocalDateTime getClosedAt() { return closedAt; }
}
