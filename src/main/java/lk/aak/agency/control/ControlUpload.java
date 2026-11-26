package lk.aak.agency.control;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A CBL report uploaded for a lorry and a day. READ means the system understood it; PROBLEM means it did not (the message says why). */
@Entity
@Table(name = "control_upload")
public class ControlUpload {

    public static final String RD_SALES = "RD_SALES";
    public static final String STOCK_BALANCE = "STOCK_BALANCE";
    public static final String READ = "READ";
    public static final String PROBLEM = "PROBLEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @Column(name = "vehicle_code", nullable = false, length = 60)
    private String vehicleCode;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "stored_name", nullable = false, length = 255)
    private String storedName;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(length = 500)
    private String message;

    @Column(name = "rows_read")
    private Integer rowsRead;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt = BusinessTime.now();

    protected ControlUpload() { }

    public ControlUpload(LocalDate controlDate, String vehicleCode, String kind, String originalName, String storedName,
                         String fileHash, String status, String message, Integer rowsRead, String uploadedBy) {
        this.controlDate = controlDate;
        this.vehicleCode = vehicleCode;
        this.kind = kind;
        this.originalName = originalName;
        this.storedName = storedName;
        this.fileHash = fileHash;
        this.status = status;
        this.message = message != null && message.length() > 500 ? message.substring(0, 500) : message;
        this.rowsRead = rowsRead;
        this.uploadedBy = uploadedBy;
    }

    public Long getId() { return id; }
    public LocalDate getControlDate() { return controlDate; }
    public String getVehicleCode() { return vehicleCode; }
    public String getKind() { return kind; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getFileHash() { return fileHash; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public Integer getRowsRead() { return rowsRead; }
    public String getUploadedBy() { return uploadedBy; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
}
