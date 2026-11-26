package lk.aak.agency.control;

import jakarta.persistence.*;
import lk.aak.agency.util.BusinessTime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** The paper file of one lorry for one day: its bills (L1 or VS1), the cash counted (L2 or VS2) and, for the van, the stock count (VS3). */
@Entity
@Table(name = "control_lorry_day", uniqueConstraints = @UniqueConstraint(name = "uk_control_lorry_day", columnNames = {"control_date", "vehicle_code"}))
public class ControlLorryDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @Column(name = "vehicle_code", nullable = false, length = 60)
    private String vehicleCode;

    @Column(name = "rep_name", length = 100)
    private String repName;

    @Column(name = "driver_name", length = 100)
    private String driverName;

    @Column(name = "cash_counted_rep", precision = 15, scale = 2)
    private BigDecimal cashCountedRep;

    @Column(name = "cash_counted_driver", precision = 15, scale = 2)
    private BigDecimal cashCountedDriver;

    /** YES / NO answers about the rep's tablet (van only). */
    @Column(name = "tablet_stock_morning", length = 3)
    private String tabletStockMorning;

    @Column(name = "tablet_stock_after", length = 3)
    private String tabletStockAfter;

    @Column(name = "morning_stock_ok", length = 3)
    private String morningStockOk;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = BusinessTime.now();

    @OneToMany(mappedBy = "lorryDay", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<ControlBillLine> bills = new ArrayList<>();

    @OneToMany(mappedBy = "lorryDay", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<ControlStockLine> stock = new ArrayList<>();

    protected ControlLorryDay() { }

    public ControlLorryDay(LocalDate controlDate, String vehicleCode) {
        this.controlDate = controlDate;
        this.vehicleCode = vehicleCode;
    }

    public Long getId() { return id; }
    public LocalDate getControlDate() { return controlDate; }
    public String getVehicleCode() { return vehicleCode; }
    public String getRepName() { return repName; }
    public void setRepName(String repName) { this.repName = repName; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public BigDecimal getCashCountedRep() { return cashCountedRep; }
    public void setCashCountedRep(BigDecimal v) { this.cashCountedRep = v; }
    public BigDecimal getCashCountedDriver() { return cashCountedDriver; }
    public void setCashCountedDriver(BigDecimal v) { this.cashCountedDriver = v; }
    public String getTabletStockMorning() { return tabletStockMorning; }
    public void setTabletStockMorning(String v) { this.tabletStockMorning = v; }
    public String getTabletStockAfter() { return tabletStockAfter; }
    public void setTabletStockAfter(String v) { this.tabletStockAfter = v; }
    public String getMorningStockOk() { return morningStockOk; }
    public void setMorningStockOk(String v) { this.morningStockOk = v; }
    public String getUpdatedBy() { return updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch(String username) { this.updatedBy = username; this.updatedAt = BusinessTime.now(); }
    public List<ControlBillLine> getBills() { return bills; }
    public List<ControlStockLine> getStock() { return stock; }
}
