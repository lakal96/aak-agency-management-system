package lk.aak.agency.control;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A task confirmed done (DONE), or a lorry marked as not running that day (NO_RUN, task code NO_RUN), or a closed day opened for work (OPEN_DAY). */
@Entity
@Table(name = "control_task_done", uniqueConstraints = @UniqueConstraint(name = "uk_control_task_done", columnNames = {"control_date", "task_code", "vehicle_code"}))
public class ControlTaskDone {

    public static final String DONE = "DONE";
    public static final String NO_RUN = "NO_RUN";
    public static final String OPEN_DAY = "OPEN_DAY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @Column(name = "task_code", nullable = false, length = 40)
    private String taskCode;

    @Column(name = "vehicle_code", nullable = false, length = 60)
    private String vehicleCode = "";

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "done_by", nullable = false, length = 100)
    private String doneBy;

    @Column(nullable = false, length = 100)
    private String login;

    @Column(name = "done_at", nullable = false)
    private LocalDateTime doneAt = BusinessTime.now();

    @Column(length = 500)
    private String note;

    protected ControlTaskDone() { }

    public ControlTaskDone(LocalDate controlDate, String taskCode, String vehicleCode, String status, String doneBy, String login, String note) {
        this.controlDate = controlDate;
        this.taskCode = taskCode;
        this.vehicleCode = vehicleCode == null ? "" : vehicleCode;
        this.status = status;
        this.doneBy = doneBy;
        this.login = login;
        this.note = note;
    }

    public LocalDate getControlDate() { return controlDate; }
    public String getTaskCode() { return taskCode; }
    public String getVehicleCode() { return vehicleCode; }
    public String getStatus() { return status; }
    public String getDoneBy() { return doneBy; }
    public String getLogin() { return login; }
    public LocalDateTime getDoneAt() { return doneAt; }
    public String getNote() { return note; }
}
