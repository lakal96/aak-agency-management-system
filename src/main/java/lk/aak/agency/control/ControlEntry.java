package lk.aak.agency.control;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of a list that is typed into Connect Plus (the columns mean different things for each kind; see {@link ControlTaskDefs}). */
@Entity
@Table(name = "control_entry")
public class ControlEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @Column(name = "vehicle_code", nullable = false, length = 60)
    private String vehicleCode = "";

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "col_a", length = 200)
    private String colA;

    @Column(name = "col_b", length = 200)
    private String colB;

    @Column
    private Integer qty;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    protected ControlEntry() { }

    public ControlEntry(LocalDate controlDate, String vehicleCode, String kind, int lineNo, String colA, String colB, Integer qty, BigDecimal amount) {
        this.controlDate = controlDate;
        this.vehicleCode = vehicleCode == null ? "" : vehicleCode;
        this.kind = kind;
        this.lineNo = lineNo;
        this.colA = colA;
        this.colB = colB;
        this.qty = qty;
        this.amount = amount;
    }

    public LocalDate getControlDate() { return controlDate; }
    public String getVehicleCode() { return vehicleCode; }
    public String getKind() { return kind; }
    public int getLineNo() { return lineNo; }
    public String getColA() { return colA; }
    public String getColB() { return colB; }
    public Integer getQty() { return qty; }
    public BigDecimal getAmount() { return amount; }
}
