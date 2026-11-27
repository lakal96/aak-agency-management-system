package lk.aak.agency.control;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.time.LocalDateTime;

/** Whether a lorry is in use. No row means in use. */
@Entity
@Table(name = "control_lorry_setting")
public class ControlLorrySetting {

    @Id
    @Column(name = "vehicle_code", length = 60)
    private String vehicleCode;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    protected ControlLorrySetting() { }

    public ControlLorrySetting(String vehicleCode) { this.vehicleCode = vehicleCode; }

    public String getVehicleCode() { return vehicleCode; }
    public boolean isActive() { return active; }
    public void set(boolean active, String by) { this.active = active; this.changedBy = by; this.changedAt = BusinessTime.now(); }
}
