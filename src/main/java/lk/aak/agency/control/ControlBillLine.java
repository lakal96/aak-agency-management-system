package lk.aak.agency.control;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** One bill on the lorry sheet: its value and how it was paid. Cash with the rep and with the driver are kept apart (the van has both). */
@Entity
@Table(name = "control_bill_line")
public class ControlBillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lorry_day_id", nullable = false)
    private ControlLorryDay lorryDay;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "bill_no", nullable = false, length = 40)
    private String billNo;

    @Column(name = "bill_value", precision = 15, scale = 2)
    private BigDecimal billValue;

    @Column(name = "cash_rep", precision = 15, scale = 2)
    private BigDecimal cashRep;

    @Column(name = "cash_driver", precision = 15, scale = 2)
    private BigDecimal cashDriver;

    @Column(name = "cheque_amount", precision = 15, scale = 2)
    private BigDecimal chequeAmount;

    @Column(name = "cheque_no", length = 40)
    private String chequeNo;

    @Column(name = "credit_amount", precision = 15, scale = 2)
    private BigDecimal creditAmount;

    protected ControlBillLine() { }

    public ControlBillLine(ControlLorryDay lorryDay, int lineNo, String billNo, BigDecimal billValue, BigDecimal cashRep,
                           BigDecimal cashDriver, BigDecimal chequeAmount, String chequeNo, BigDecimal creditAmount) {
        this.lorryDay = lorryDay;
        this.lineNo = lineNo;
        this.billNo = billNo;
        this.billValue = billValue;
        this.cashRep = cashRep;
        this.cashDriver = cashDriver;
        this.chequeAmount = chequeAmount;
        this.chequeNo = chequeNo;
        this.creditAmount = creditAmount;
    }

    public int getLineNo() { return lineNo; }
    public String getBillNo() { return billNo; }
    public BigDecimal getBillValue() { return billValue; }
    public BigDecimal getCashRep() { return cashRep; }
    public BigDecimal getCashDriver() { return cashDriver; }
    public BigDecimal getChequeAmount() { return chequeAmount; }
    public String getChequeNo() { return chequeNo; }
    public BigDecimal getCreditAmount() { return creditAmount; }
}
