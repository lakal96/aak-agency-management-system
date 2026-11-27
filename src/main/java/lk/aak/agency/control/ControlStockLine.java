package lk.aak.agency.control;

import jakarta.persistence.*;

/** One product on the stock count sheet (VS3): what was counted on the lorry tonight and what the rep wants loaded tomorrow. */
@Entity
@Table(name = "control_stock_line")
public class ControlStockLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lorry_day_id", nullable = false)
    private ControlLorryDay lorryDay;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column
    private Integer counted;

    @Column(name = "load_tomorrow")
    private Integer loadTomorrow;

    protected ControlStockLine() { }

    public ControlStockLine(ControlLorryDay lorryDay, int lineNo, String productName, Integer counted, Integer loadTomorrow) {
        this.lorryDay = lorryDay;
        this.lineNo = lineNo;
        this.productName = productName;
        this.counted = counted;
        this.loadTomorrow = loadTomorrow;
    }

    public int getLineNo() { return lineNo; }
    public String getProductName() { return productName; }
    public Integer getCounted() { return counted; }
    public Integer getLoadTomorrow() { return loadTomorrow; }
}
