package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The richer half of the dashboard: one month of sales against the same stretch of the month before,
 * what was sold by category, and today's deliveries. Everything is real data - no placeholder points.
 */
public record DashboardOverviewResponse(
        String month,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        LocalDate comparisonStart,
        LocalDate comparisonEnd,
        Metric sales,
        Metric orders,
        Metric buyingCustomers,
        List<CategorySale> categories,
        Deliveries deliveriesToday) {

    /** value over the range, the same measure over the comparison range, % change (null when there is nothing to compare), and a day-by-day series. */
    public record Metric(BigDecimal value, BigDecimal previous, BigDecimal changePercent, List<DayPoint> daily) {
    }

    public record DayPoint(LocalDate date, BigDecimal value) {
    }

    public record CategorySale(String category, BigDecimal amount, BigDecimal percent) {
    }

    /** Bills on today's trips: waiting at the warehouse, out on the lorry, delivered, or needing attention. */
    public record Deliveries(LocalDate date, long pending, long onRoute, long delivered, long needAttention) {
    }
}
