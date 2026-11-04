package lk.aak.agency.service;

import lk.aak.agency.dto.api.DashboardOverviewResponse;
import lk.aak.agency.dto.api.DashboardOverviewResponse.CategorySale;
import lk.aak.agency.dto.api.DashboardOverviewResponse.DayPoint;
import lk.aak.agency.dto.api.DashboardOverviewResponse.Deliveries;
import lk.aak.agency.dto.api.DashboardOverviewResponse.Metric;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class DashboardOverviewService {

    /** The agency works on Sri Lanka time; "today" must not be the UTC date the server happens to run on. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");

    private static final int MAX_NAMED_CATEGORIES = 5;

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceItemRepository salesInvoiceItemRepository;
    private final Clock clock;

    @Autowired
    public DashboardOverviewService(
            SalesInvoiceRepository salesInvoiceRepository, SalesInvoiceItemRepository salesInvoiceItemRepository) {
        this(salesInvoiceRepository, salesInvoiceItemRepository, Clock.system(BUSINESS_ZONE));
    }

    DashboardOverviewService(
            SalesInvoiceRepository salesInvoiceRepository,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            Clock clock) {
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
        this.clock = clock;
    }

    /**
     * @param month      the month to show (null = the current month); never later than the current month
     * @param employeeId scopes everything to one sales rep's shops; null = the whole agency (ADMIN/OFFICE)
     */
    @Transactional(readOnly = true)
    public DashboardOverviewResponse overview(YearMonth month, Long employeeId) {

        LocalDate today = LocalDate.now(clock);
        YearMonth current = YearMonth.from(today);
        YearMonth shown = month == null ? current : month;
        if (shown.isAfter(current)) {
            throw new IllegalArgumentException("That month has not started yet.");
        }

        LocalDate rangeStart = shown.atDay(1);
        LocalDate rangeEnd = shown.equals(current) ? today : shown.atEndOfMonth();

        // Compare like with like: 1-12 November against 1-12 October, not against all of October.
        YearMonth previous = shown.minusMonths(1);
        LocalDate comparisonStart = previous.atDay(1);
        LocalDate comparisonEnd = previous.atDay(Math.min(rangeEnd.getDayOfMonth(), previous.lengthOfMonth()));

        Map<LocalDate, BigDecimal[]> now = totalsByDay(rangeStart, rangeEnd, employeeId);
        Map<LocalDate, BigDecimal[]> before = totalsByDay(comparisonStart, comparisonEnd, employeeId);

        Metric sales = metric(rangeStart, rangeEnd, now, before, 1);
        Metric orders = metric(rangeStart, rangeEnd, now, before, 0);
        Metric buyers = buyingCustomers(rangeStart, rangeEnd, comparisonStart, comparisonEnd, employeeId);

        return new DashboardOverviewResponse(
                shown.toString(),
                rangeStart,
                rangeEnd,
                comparisonStart,
                comparisonEnd,
                sales,
                orders,
                buyers,
                categories(rangeStart, rangeEnd, employeeId, sales.value()),
                deliveries(today, employeeId));
    }

    /** day -> [order count, net amount] */
    private Map<LocalDate, BigDecimal[]> totalsByDay(LocalDate from, LocalDate to, Long employeeId) {
        Map<LocalDate, BigDecimal[]> byDay = new TreeMap<>();
        for (Object[] row : salesInvoiceRepository.completedTotalsByDay(from, to, employeeId)) {
            byDay.put(
                    (LocalDate) row[0],
                    new BigDecimal[] {BigDecimal.valueOf(((Number) row[1]).longValue()), (BigDecimal) row[2]});
        }
        return byDay;
    }

    /** @param slot 0 = number of orders, 1 = sales amount */
    private Metric metric(
            LocalDate from, LocalDate to, Map<LocalDate, BigDecimal[]> now, Map<LocalDate, BigDecimal[]> before, int slot) {

        List<DayPoint> daily = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            BigDecimal[] totals = now.get(day);
            BigDecimal value = totals == null ? BigDecimal.ZERO : totals[slot];
            daily.add(new DayPoint(day, value));
            total = total.add(value);
        }

        BigDecimal previousTotal = before.values().stream().map(t -> t[slot]).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Metric(total, previousTotal, changePercent(total, previousTotal), daily);
    }

    /** Shops that bought in the range (each shop counted once); the series is the running count of distinct shops. */
    private Metric buyingCustomers(
            LocalDate from, LocalDate to, LocalDate comparisonFrom, LocalDate comparisonTo, Long employeeId) {

        Map<LocalDate, Set<Long>> shopsByDay = new TreeMap<>();
        for (Object[] row : salesInvoiceRepository.buyingCustomersByDay(from, to, employeeId)) {
            shopsByDay.computeIfAbsent((LocalDate) row[0], d -> new HashSet<>()).add(((Number) row[1]).longValue());
        }

        List<DayPoint> daily = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            seen.addAll(shopsByDay.getOrDefault(day, Set.of()));
            daily.add(new DayPoint(day, BigDecimal.valueOf(seen.size())));
        }

        Set<Long> previousShops = new HashSet<>();
        for (Object[] row : salesInvoiceRepository.buyingCustomersByDay(comparisonFrom, comparisonTo, employeeId)) {
            previousShops.add(((Number) row[1]).longValue());
        }

        BigDecimal value = BigDecimal.valueOf(seen.size());
        BigDecimal previous = BigDecimal.valueOf(previousShops.size());
        return new Metric(value, previous, changePercent(value, previous), daily);
    }

    /** The biggest categories by name, everything smaller rolled into "Others". */
    private List<CategorySale> categories(LocalDate from, LocalDate to, Long employeeId, BigDecimal netSales) {

        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : salesInvoiceItemRepository.completedSalesByCategory(from, to, employeeId)) {
            rows.add(new Object[] {row[0], ((BigDecimal) row[1]).setScale(2, RoundingMode.HALF_UP)});
        }
        rows.removeIf(row -> ((BigDecimal) row[1]).signum() <= 0);
        rows.sort(Comparator.comparing((Object[] row) -> (BigDecimal) row[1]).reversed());

        // Each category is rounded to the cent, so the sum can be a few cents off the net sales figure:
        // put that rounding difference on the biggest category so the categories add up exactly. A larger
        // gap is real (a data problem) and is left visible rather than hidden.
        if (!rows.isEmpty()) {
            BigDecimal sum = rows.stream().map(row -> (BigDecimal) row[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal difference = netSales.subtract(sum);
            if (difference.signum() != 0 && difference.abs().compareTo(new BigDecimal("0.50")) <= 0) {
                rows.get(0)[1] = ((BigDecimal) rows.get(0)[1]).add(difference);
            }
        }

        BigDecimal total = rows.stream().map(row -> (BigDecimal) row[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return List.of();
        }

        List<CategorySale> result = new ArrayList<>();
        BigDecimal others = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            BigDecimal amount = (BigDecimal) rows.get(i)[1];
            if (i < MAX_NAMED_CATEGORIES) {
                result.add(new CategorySale((String) rows.get(i)[0], amount, share(amount, total)));
            } else {
                others = others.add(amount);
            }
        }
        if (others.signum() > 0) {
            result.add(new CategorySale("Others", others, share(others, total)));
        }
        return result;
    }

    private Deliveries deliveries(LocalDate today, Long employeeId) {

        long pending = 0;
        long onRoute = 0;
        long delivered = 0;
        long needAttention = 0;

        for (Object[] row : salesInvoiceRepository.deliveryCountsForDay(today, employeeId)) {
            String billStatus = row[0] == null ? "PENDING" : ((String) row[0]).toUpperCase();
            String tripStatus = row[1] == null ? "" : ((String) row[1]).toUpperCase();
            long count = ((Number) row[2]).longValue();

            if ("COMPLETED".equals(billStatus)) {
                delivered += count;
            } else if ("PARTIAL".equals(billStatus) || "UNSUCCESSFUL".equals(billStatus)) {
                needAttention += count;
            } else if ("LOADED".equals(tripStatus)) {
                onRoute += count;
            } else if ("COMPLETED".equals(tripStatus)) {
                // The trip was closed but this bill never got a result: someone must account for it.
                needAttention += count;
            } else {
                pending += count;
            }
        }
        return new Deliveries(today, pending, onRoute, delivered, needAttention);
    }

    private static BigDecimal changePercent(BigDecimal value, BigDecimal previous) {
        if (previous.signum() == 0) {
            return null;
        }
        return value.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
