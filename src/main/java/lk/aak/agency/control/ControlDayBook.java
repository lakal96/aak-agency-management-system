package lk.aak.agency.control;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** The safe and the end of the day for all lorries together (sheets DC and CC), plus the owner's signature the next morning. */
@Entity
@Table(name = "control_day_book", uniqueConstraints = @UniqueConstraint(name = "uk_control_day_book", columnNames = "control_date"))
public class ControlDayBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @Column(name = "safe_opening", precision = 15, scale = 2)
    private BigDecimal safeOpening;

    @Column(precision = 15, scale = 2)
    private BigDecimal banked;

    @Column(name = "paid_out", precision = 15, scale = 2)
    private BigDecimal paidOut;

    @Column(name = "safe_counted", precision = 15, scale = 2)
    private BigDecimal safeCounted;

    @Column(name = "day_end_done", nullable = false)
    private boolean dayEndDone;

    @Column(name = "signed_by", length = 100)
    private String signedBy;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = BusinessTime.now();

    protected ControlDayBook() { }

    public ControlDayBook(LocalDate controlDate) { this.controlDate = controlDate; }

    public LocalDate getControlDate() { return controlDate; }
    public BigDecimal getSafeOpening() { return safeOpening; }
    public void setSafeOpening(BigDecimal v) { this.safeOpening = v; }
    public BigDecimal getBanked() { return banked; }
    public void setBanked(BigDecimal v) { this.banked = v; }
    public BigDecimal getPaidOut() { return paidOut; }
    public void setPaidOut(BigDecimal v) { this.paidOut = v; }
    public BigDecimal getSafeCounted() { return safeCounted; }
    public void setSafeCounted(BigDecimal v) { this.safeCounted = v; }
    public boolean isDayEndDone() { return dayEndDone; }
    public void setDayEndDone(boolean v) { this.dayEndDone = v; }
    public String getSignedBy() { return signedBy; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public void sign(String username) { this.signedBy = username; this.signedAt = BusinessTime.now(); }
    public void touch(String username) { this.updatedBy = username; this.updatedAt = BusinessTime.now(); }
}
